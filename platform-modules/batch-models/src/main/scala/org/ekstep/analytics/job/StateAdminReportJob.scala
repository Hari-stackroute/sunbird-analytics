package org.ekstep.analytics.job

import org.apache.spark.SparkContext
import org.apache.spark.sql._
import org.apache.spark.sql.functions.{ col, lit, _ }
import org.apache.spark.sql.types.StructType
import org.ekstep.analytics.framework._
import org.ekstep.analytics.framework.util.{ JSONUtils, JobLogger }
import org.ekstep.analytics.util.HDFSFileUtils
import org.sunbird.cloud.storage.conf.AppConf

case class UserStatus(id: Long, status: String)
object UnclaimedStatus extends UserStatus(0, "UNCLAIMED")
object ClaimedStatus extends UserStatus(1, "CLAIMED")
object RejectedStatus extends UserStatus(2, "REJECTED")
object FailedStatus extends UserStatus(3, "FAILED")
object MultiMatchStatus extends UserStatus(4, "MULTIMATCH")
object OrgExtIdMismatch extends UserStatus(5, "ORGEXTIDMISMATCH")

case class ShadowUserData(channel: String, userextid: String, addedby: String, claimedon: java.sql.Timestamp, claimstatus: Int,
                          createdon: java.sql.Timestamp, email: String, name: String, orgextid: String, processid: String,
                          phone: String, updatedon: java.sql.Timestamp, userid: String, userids: List[String], userstatus: Int)

case class RootOrgData(rootorgjoinid: String, rootorgchannel: String)

// Shadow user summary in the json will have this POJO
case class UserSummary(accounts_validated: Long, accounts_rejected: Long, accounts_unclaimed: Long, accounts_failed: Long)

// Geo user summary in the json will have this POJO
case class GeoSummary(channel: String, districts: Long, blocks: Long, school: Long)

case class SubOrgRow(id: String, isrootorg: Boolean, rootorgid: String, channel: String, status: String, locationid: String, locationids: Seq[String],
                     exploded_location: String, locid: String, code: String, name: String, parentid: String, loctype: String, rootorgjoinid: String, rootorgchannel: String)

object StateAdminReportJob extends optional.Application with IJob with BaseReportsJob {

  implicit val className: String = "org.ekstep.analytics.job.StateAdminReportJob"

  def name(): String = "StateAdminReportJob"

  private val DETAIL_STR = "detail"
  private val SUMMARY_STR = "summary"

  def main(config: String)(implicit sc: Option[SparkContext] = None) {

    JobLogger.init(name())
    JobLogger.start("Started executing", Option(Map("config" -> config, "model" -> name)))
    val jobConfig = JSONUtils.deserialize[JobConfig](config)
    JobContext.parallelization = 10

    implicit val sparkSession: SparkSession = openSparkSession(jobConfig);
    execute(jobConfig)
    closeSparkSession();
  }

  private def execute(config: JobConfig)(implicit sparkSession: SparkSession) = {

    val tempDir = AppConf.getConfig("admin.metrics.temp.dir")
    val renamedDir = s"$tempDir/renamed"

    generateReport()
    uploadReport(renamedDir)
    JobLogger.end("StateAdminReportJob completed successfully!", "SUCCESS", Option(Map("config" -> config, "model" -> name)))
  }

  def generateReport()(implicit sparkSession: SparkSession) {

    generateShadowDBReport();
    generateGeoSummaryReport();

    JobLogger.log("Finished with generateReport")
  }

  private def generateShadowDBReport()(implicit sparkSession: SparkSession) {

    import sparkSession.implicits._
    val sunbirdKeyspace = AppConf.getConfig("course.metrics.cassandra.sunbirdKeyspace")
    val tempDir = AppConf.getConfig("admin.metrics.temp.dir")
    val renamedDir = s"$tempDir/renamed"
    val fSFileUtils = new HDFSFileUtils(className, JobLogger)
    val detailDir = s"$tempDir/detail"
    val summaryDir = s"$tempDir/summary"

    val shadowDataEncoder = Encoders.product[ShadowUserData].schema
    val shadowUserDF = loadData(sparkSession, Map("table" -> "shadow_user", "keyspace" -> sunbirdKeyspace), Some(shadowDataEncoder)).as[ShadowUserData]

    val shadowDataSummary = generateSummaryData(shadowUserDF)

    saveUserSummaryReport(shadowDataSummary, s"$summaryDir")
    saveUserDetailsReport(shadowUserDF.toDF(), s"$detailDir")

    fSFileUtils.renameReport(detailDir, renamedDir, ".csv", "user-detail")
    fSFileUtils.renameReport(summaryDir, renamedDir, ".json", "user-summary")

    // Purge the directories after copying to the upload staging area
    fSFileUtils.purgeDirectory(detailDir)
    fSFileUtils.purgeDirectory(summaryDir)
  }

  private def generateGeoSummaryReport()(implicit sparkSession: SparkSession) {
    import sparkSession.implicits._
    val sunbirdKeyspace = AppConf.getConfig("course.metrics.cassandra.sunbirdKeyspace")
    val tempDir = AppConf.getConfig("admin.metrics.temp.dir")
    val renamedDir = s"$tempDir/renamed"

    val fSFileUtils = new HDFSFileUtils(className, JobLogger)

    val detailDir = s"$tempDir/detail"
    val summaryDir = s"$tempDir/summary"

    val locationDF = loadData(sparkSession, Map("table" -> "location", "keyspace" -> sunbirdKeyspace), None).select(
      col("id").as("locid"),
      col("code").as("code"),
      col("name").as("name"),
      col("parentid").as("parentid"),
      col("type").as("loctype"))

    val organisationDF = loadData(sparkSession, Map("table" -> "organisation", "keyspace" -> sunbirdKeyspace), None).select(
      col("id").as("id"),
      col("isrootorg").as("isrootorg"),
      col("rootorgid").as("rootorgid"),
      col("channel").as("channel"),
      col("status").as("status"),
      col("locationid").as("locationid"),
      col("locationids").as("locationids")).cache();

    val rootOrgs = organisationDF.select(col("id").as("rootorgjoinid"), col("channel").as("rootorgchannel")).where(col("isrootorg") && col("status").===(1)).collect();
    val rootOrgRDD = sparkSession.sparkContext.parallelize(rootOrgs.toSeq);
    val rootOrgEncoder = Encoders.product[RootOrgData].schema
    val rootOrgDF = sparkSession.createDataFrame(rootOrgRDD, rootOrgEncoder);

    val subOrgDF = organisationDF
      .withColumn("exploded_location", explode(when(size(col("locationids")).equalTo(0), array(lit(null).cast("string")))
        .otherwise(when(col("locationids").isNotNull, col("locationids"))
          .otherwise(array(lit(null).cast("string"))))))

    val subOrgJoinedDF = subOrgDF
      .join(locationDF, subOrgDF.col("exploded_location") === locationDF.col("locid"), "left")
      .join(rootOrgDF, subOrgDF.col("rootorgid") === rootOrgDF.col("rootorgjoinid")).as[SubOrgRow]

    subOrgJoinedDF
      .groupBy("channel")
      .agg(countDistinct("id").as("school"), count(col("locType").equalTo("district")).as("districts"), 
          count(col("locType").equalTo("block")).as("blocks"))
      .coalesce(1)
      .write
      .partitionBy("channel")
      .mode("overwrite")
      .json(s"$summaryDir")

    fSFileUtils.renameReport(summaryDir, renamedDir, ".json", "geo-summary")
  }

  def generateSummaryData(shadowUserDF: Dataset[ShadowUserData])(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    def transformClaimedStatusValue()(ds: Dataset[ShadowUserData]) = {
      ds.withColumn(
        "claim_status",
        when($"claimstatus" === UnclaimedStatus.id, lit(UnclaimedStatus.status))
          .when($"claimstatus" === ClaimedStatus.id, lit(ClaimedStatus.status))
          .when($"claimstatus" === FailedStatus.id, lit(FailedStatus.status))
          .when($"claimstatus" === RejectedStatus.id, lit(RejectedStatus.status))
          .when($"claimstatus" === MultiMatchStatus.id, lit(MultiMatchStatus.status))
          .when($"claimstatus" === OrgExtIdMismatch.id, lit(OrgExtIdMismatch.status))
          .otherwise(lit("")))
    }

    val shadowDataSummary = shadowUserDF.transform(transformClaimedStatusValue()).groupBy("channel")
      .pivot("claim_status").agg(count("claim_status")).na.fill(0)

    shadowDataSummary.show(10, false)
    shadowDataSummary
  }

  /**
   * Saves the raw data as a .csv.
   * Appends /detail to the URL to prevent overwrites.
   * Check function definition for the exact column ordering.
   * @param reportDF
   * @param url
   */
  def saveUserDetailsReport(reportDF: DataFrame, url: String): Unit = {
    // List of fields available
    //channel,userextid,addedby,claimedon,claimstatus,createdon,email,name,orgextid,phone,processid,updatedon,userid,userids,userstatus

    reportDF.coalesce(1)
      .select(
        col("channel"),
        col("userextid").as("User external id"),
        col("userstatus").as("User account status"),
        col("userid").as("User id"),
        concat_ws(",", col("userids")).as("Matching User ids"),
        col("claimedon").as("Claimed on"),
        col("orgextid").as("School external id"),
        col("claimstatus").as("Claimed status"),
        col("createdon").as("Created on"),
        col("updatedon").as("Last updated on"))
      .write
      .partitionBy("channel")
      .mode("overwrite")
      .option("header", "true")
      .csv(url)

    JobLogger.log(s"StateAdminReportJob: uploadedSuccess nRecords = ${reportDF.count()}")
  }

  def saveGeoDetailsReport(reportDF: DataFrame, url: String): Unit = {
    reportDF.coalesce(1)
      .select(
        col("id").as("School id"),
        col("orgname").as("School name"),
        col("channel").as("Channel"),
        col("status").as("Status"),
        col("locid").as("Location id"),
        col("name").as("Location name"),
        col("parentid").as("Parent location id"),
        col("type").as("type"))
      .write
      .mode("overwrite")
      .option("header", "true")
      .csv(url)

    JobLogger.log(s"StateAdminReportJob: uploadedSuccess nRecords = ${reportDF.count()}")
  }

  /**
   * Saves the raw data as a .json.
   * Appends /summary to the URL to prevent overwrites.
   * Check function definition for the exact column ordering.
   * * If we don't partition, the reports get subsequently updated and we dont want so
   * @param reportDF
   * @param url
   */
  def saveSummaryReport(reportDF: DataFrame, url: String): Unit = {
    reportDF.coalesce(1)
      .write
      .partitionBy("channel")
      .mode("overwrite")
      .json(url)

    JobLogger.log(s"StateAdminReportJob: uploadedSuccess nRecords = ${reportDF.count()}")
    println(s"StateAdminReportJob: uploadedSuccess nRecords = ${reportDF.count()} and ${url}")
  }

  def saveUserSummaryReport(reportDF: DataFrame, url: String): Unit = {
    val dfColumns = reportDF.columns.toSet

    // Get claim status not in the current dataframe to add them.
    val columns: Seq[String] = Seq(
      UnclaimedStatus.status,
      ClaimedStatus.status,
      RejectedStatus.status,
      FailedStatus.status,
      MultiMatchStatus.status,
      OrgExtIdMismatch.status).filterNot(dfColumns)
    val correctedReportDF = columns.foldLeft(reportDF)((acc, col) => {
      acc.withColumn(col, lit(0))
    })
    JobLogger.log(s"columns to add in this report $columns")

    correctedReportDF.coalesce(1)
      .select(
        col("channel"),
        when(col(UnclaimedStatus.status).isNull, 0).otherwise(col(UnclaimedStatus.status)).as("accounts_unclaimed"),
        when(col(ClaimedStatus.status).isNull, 0).otherwise(col(ClaimedStatus.status)).as("accounts_validated"),
        when(col(RejectedStatus.status).isNull, 0).otherwise(col(RejectedStatus.status)).as("accounts_rejected"),
        when(col(FailedStatus.status).isNull, 0).otherwise(col(FailedStatus.status)).as(FailedStatus.status),
        when(col(MultiMatchStatus.status).isNull, 0).otherwise(col(MultiMatchStatus.status)).as(MultiMatchStatus.status),
        when(col(OrgExtIdMismatch.status).isNull, 0).otherwise(col(OrgExtIdMismatch.status)).as(OrgExtIdMismatch.status))
      .withColumn(
        "accounts_failed",
        col(FailedStatus.status) + col(MultiMatchStatus.status) + col(OrgExtIdMismatch.status))
      .write
      .partitionBy("channel")
      .mode("overwrite")
      .json(url)

    JobLogger.log(s"StateAdminReportJob: uploadedSuccess nRecords = ${reportDF.count()}")
  }

  def uploadReport(sourcePath: String) = {
    // Container name can be generic - we dont want to create as many container as many reports
    val container = AppConf.getConfig("cloud.container.reports")
    val objectKey = AppConf.getConfig("admin.metrics.cloud.objectKey")

    reportStorageService.upload(container, sourcePath, objectKey, isDirectory = Option(true))
  }
}

object StateAdminReportJobTest {

  def main(args: Array[String]): Unit = {
    StateAdminReportJob.main("""{"model":"Test"}""");
  }
}
