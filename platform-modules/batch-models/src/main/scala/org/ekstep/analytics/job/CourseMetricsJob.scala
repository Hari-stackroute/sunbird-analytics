package org.ekstep.analytics.job

import org.apache.spark.SparkContext
import org.apache.spark.sql.functions.{col, unix_timestamp, _}
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.ekstep.analytics.framework._
import org.ekstep.analytics.framework.util.{CommonUtil, JSONUtils, JobLogger}
import org.sunbird.cloud.storage.conf.AppConf
import java.nio.file.{Files, StandardCopyOption}
import java.io.File

import org.sunbird.cloud.storage.factory.{StorageConfig, StorageServiceFactory}

import scala.collection.{Map, _}

trait ReportGenerator {
  def loadData(spark: SparkSession, settings: Map[String, String]): DataFrame

  def prepareReport(spark: SparkSession, fetchTable: (SparkSession, Map[String, String]) => DataFrame): DataFrame

  def saveReportES(reportDF: DataFrame): Unit

  def saveReport(reportDF: DataFrame, url: String): Unit
}

object CourseMetricsJob extends optional.Application with IJob with ReportGenerator {

  implicit val className = "org.ekstep.analytics.job.CourseMetricsJob"

  def name(): String = "CourseMetricsJob"

  def main(config: String)(implicit sc: Option[SparkContext] = None) {

    JobLogger.init("CourseMetricsJob")
    JobLogger.start("CourseMetrics Job Started executing", Option(Map("config" -> config, "model" -> name)))
    val jobConfig = JSONUtils.deserialize[JobConfig](config)
    JobContext.parallelization = 10

    def runJob(sc: SparkContext): Unit = {
      try {
        execute(jobConfig)(sc)
      } finally {
        CommonUtil.closeSparkContext()(sc)
      }
    }

    sc match {
      case Some(value) => {
        implicit val sparkContext: SparkContext = value
        runJob(value)
      }
      case None => {
        val sparkCassandraConnectionHost =
          jobConfig.modelParams.getOrElse(Map[String, Option[AnyRef]]()).get("sparkCassandraConnectionHost")
        val sparkElasticsearchConnectionHost =
          jobConfig.modelParams.getOrElse(Map[String, Option[AnyRef]]()).get("sparkElasticsearchConnectionHost")
        implicit val sparkContext: SparkContext =
          CommonUtil.getSparkContext(JobContext.parallelization,
            jobConfig.appName.getOrElse(jobConfig.model), sparkCassandraConnectionHost, sparkElasticsearchConnectionHost)
        runJob(sparkContext)
      }
    }
  }

  private def execute(config: JobConfig)(implicit sc: SparkContext) = {
    val tempDir = AppConf.getConfig("course.metrics.temp.dir")
    val readConsistencyLevel: String = AppConf.getConfig("course.metrics.cassandra.input.consistency")
    val renamedDir = s"$tempDir/renamed"
    val sparkConf = sc.getConf
      .set("es.write.operation", "upsert")
      .set("spark.cassandra.input.consistency.level", readConsistencyLevel)

    val spark = SparkSession.builder.config(sparkConf).getOrCreate()
    val reportDF = prepareReport(spark, loadData)
    saveReport(reportDF, tempDir)
    renameReport(tempDir, renamedDir)
    uploadReport(renamedDir)
    saveReportES(reportDF)
  }

  def loadData(spark: SparkSession, settings: Map[String, String]): DataFrame = {
    spark
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(settings)
      .load()
  }

  def prepareReport(spark: SparkSession, loadData: (SparkSession, Map[String, String]) => DataFrame): DataFrame = {
    val sunbirdKeyspace = AppConf.getConfig("course.metrics.cassandra.keyspace")
    val courseBatchDF = loadData(spark, Map("table" -> "course_batch", "keyspace" -> sunbirdKeyspace))
    val userCoursesDF = loadData(spark, Map("table" -> "user_courses", "keyspace" -> sunbirdKeyspace))
    val userDF = loadData(spark, Map("table" -> "user", "keyspace" -> sunbirdKeyspace))
    val userOrgDF = loadData(spark, Map("table" -> "user_org", "keyspace" -> sunbirdKeyspace)).filter(lower(col("isdeleted")) === "false")
    val organisationDF = loadData(spark, Map("table" -> "organisation", "keyspace" -> sunbirdKeyspace))
    val locationDF = loadData(spark, Map("table" -> "location", "keyspace" -> sunbirdKeyspace))

    /*
    * courseBatchDF has details about the course and batch details for which we have to prepare the report
    * courseBatchDF is the primary source for the report
    * userCourseDF has details about the user details enrolled for a particular course/batch
    * */
    val userCourseDenormDF = courseBatchDF.join(userCoursesDF, userCoursesDF.col("batchid") === courseBatchDF.col("id") && lower(userCoursesDF.col("active")).equalTo("true"), "inner")
      .select(col("batchid"),
        col("userid"),
        col("leafnodescount"),
        col("progress"),
        col("enddate"),
        col("startdate"),
        col("enrolleddate"),
        col("active"),
        courseBatchDF.col("courseid"))

    /*
    *userCourseDenormDF lacks some of the user information that need to be part of the report
    *here, it will add some more user details
    * */
    val userDenormDF = userCourseDenormDF
      .join(userDF, Seq("userid"), "inner")
      .select(
        userCourseDenormDF.col("*"),
        col("firstname"),
        col("lastname"),
        col("maskedemail"),
        col("maskedphone"),
        col("rootorgid"),
        col("locationids"))

    /*
    * userDenormDF lacks organisation details, here we are mapping each users to get the organisationids
    * */
    val userOrgDenormDF = userDenormDF
      .join(userOrgDF, userOrgDF.col("userid") === userDenormDF.col("userid"), "inner")
      .select(userDenormDF.col("*"), col("organisationid"))

    val locationDenormDF = userOrgDenormDF
      .withColumn("exploded_location", explode(col("locationids")))
      .join(locationDF, col("exploded_location") === locationDF.col("id") && locationDF.col("type") === "district")
      .groupBy("userid", "exploded_location")
      .agg(concat_ws(",", collect_list(locationDF.col("name"))) as "district_name")
      .drop(col("exploded_location"))

    val userLocationResolvedDF = userOrgDenormDF
      .join(locationDenormDF, Seq("userid"), "left_outer")

    /*
    * Resolve organisation name from `rootorgid`
    * */
    val resolvedOrgNameDF = userLocationResolvedDF
      .join(organisationDF, organisationDF.col("id") === userLocationResolvedDF.col("rootorgid"), "left_outer")
      .dropDuplicates(Seq("userid"))
      .select(userLocationResolvedDF.col("userid"), col("orgname").as("orgname_resolved"))


    /*
    * Resolve school name from `orgid`
    * */
    val resolvedSchoolNameDF = userLocationResolvedDF
      .join(organisationDF, organisationDF.col("id") === userLocationResolvedDF.col("organisationid"), "left_outer")
      .dropDuplicates(Seq("userid"))
      .select(userLocationResolvedDF.col("userid"), col("orgname").as("schoolname_resolved"))

    /*
    * merge orgName and schoolName based on `userid` and calculate the course progress percentage from `progress` column which is no of content visited/read
    * */

    userLocationResolvedDF
      .join(resolvedSchoolNameDF, Seq("userid"), "left_outer")
      .join(resolvedOrgNameDF, Seq("userid"), "left_outer")
      .withColumn("course_completion", format_number(expr("progress/leafnodescount * 100"), 2).cast("double"))
      .withColumn("generatedOn", date_format(from_utc_timestamp(current_timestamp.cast(DataTypes.TimestampType), "Asia/Kolkata"), "yyyy-MM-dd'T'HH:mm:ssXXX'Z'"))
  }


  def saveReportES(reportDF: DataFrame): Unit = {

    import org.elasticsearch.spark.sql._
    val participantsCountPerBatchDF = reportDF
      .groupBy(col("batchid"))
      .count()
      .withColumn("participantsCountPerBatch", col("count"))
      .drop(col("count"))

    val courseCompletionCountPerBatchDF = reportDF
      .filter(col("course_completion").equalTo(100.0))
      .groupBy(col("batchid"))
      .count()
      .withColumn("courseCompletionCountPerBatch", col("count"))
      .drop(col("count"))

    val batchStatsDF = participantsCountPerBatchDF
      .join(courseCompletionCountPerBatchDF, Seq("batchid"), "left_outer")
      .join(reportDF, Seq("batchid"))
      .select(
        concat_ws(" ", col("firstname"), col("lastname")).as("name"),
        concat_ws(":", col("userid"), col("batchid")).as("id"),
        col("userid").as("userId"),
        col("maskedemail").as("maskedEmail"),
        col("maskedphone").as("maskedPhone"),
        col("orgname_resolved").as("rootOrgName"),
        col("schoolname_resolved").as("subOrgName"),
        col("startdate").as("startDate"),
        col("enddate").as("endDate"),
        col("courseid").as("courseId"),
        col("generatedOn").as("lastUpdatedOn"),
        col("batchid").as("batchId"),
        when(col("course_completion").isNull, 100)
          .otherwise(col("course_completion").cast("long"))
          .as("completedPercent"),
        col("district_name").as("districtName"),
        from_unixtime(unix_timestamp(col("enrolleddate"), "yyyy-MM-dd HH:mm:ss:SSSZ"),"yyyy-MM-dd'T'HH:mm:ss'Z'").as("enrolledOn")
      )

    val batchDetailsDF = participantsCountPerBatchDF
      .join(courseCompletionCountPerBatchDF, Seq("batchid"), "left_outer")
      .join(reportDF, Seq("batchid"))
      .select(
        col("batchid").as("id"),
        when(col("courseCompletionCountPerBatch").isNull, 0).otherwise(col("courseCompletionCountPerBatch")).as("completedCount"),
        when(col("participantsCountPerBatch").isNull, 0).otherwise(col("participantsCountPerBatch")).as("participantCount")
      )
      .distinct()

    val cBatchStatsIndex = AppConf.getConfig("course.metrics.es.index.cbatchstats")
    val cBatchIndex = AppConf.getConfig("course.metrics.es.index.cbatch")

    //Save to sunbird platform Elasticsearch instance
    // upsert batch stats to cbatchstats index
    batchStatsDF.saveToEs(s"$cBatchStatsIndex/_doc", Map("es.mapping.id" -> "id"))

    // upsert batch details to cbatch index
    batchDetailsDF.saveToEs(s"$cBatchIndex/_doc", Map("es.mapping.id" -> "id"))
  }


  def saveReport(reportDF: DataFrame, url: String): Unit = {

    reportDF
      .select(
        concat_ws(" ", col("firstname"), col("lastname")).as("User Name"),
        col("batchid"),
        col("maskedemail").as("Email ID"),
        col("maskedphone").as("Mobile Number"),
        col("district_name").as("District Name"),
        col("orgname_resolved").as("Organisation Name"),
        col("schoolname_resolved").as("School Name"),
        col("enrolleddate").as("Enrollment Date"),
        concat(
          when(col("course_completion").isNull, "100")
            .otherwise(col("course_completion").cast("string")),
          lit("%")
        ).as("Course Progress")
      )
      .coalesce(1)
      .write
      .partitionBy("batchid")
      .mode("overwrite")
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .save(url)
  }

  def uploadReport(sourcePath: String) = {
    val provider = AppConf.getConfig("course.metrics.cloud.provider")
    val container = AppConf.getConfig("course.metrics.cloud.container")
    val objectKey = AppConf.getConfig("course.metrics.cloud.objectKey")

    val storageService = StorageServiceFactory
      .getStorageService(StorageConfig(provider, AppConf.getStorageKey(provider), AppConf.getStorageSecret(provider)))
    storageService.upload(container, sourcePath, objectKey, isDirectory = Option(true))
  }

  private def recursiveListFiles(file: File, ext: String): Array[File] = {
    val fileList = file.listFiles
    val extOnly = fileList.filter(file => file.getName.endsWith(ext))
    extOnly ++ fileList.filter(_.isDirectory).flatMap(recursiveListFiles(_,ext))
  }

  private def purgeDirectory(dir: File): Unit = {
    for (file <- dir.listFiles) {
      if (file.isDirectory) purgeDirectory(file)
      file.delete
    }
  }

  def renameReport(tempDir: String, outDir: String) = {
    val regex = """\=.*/""".r // to get batchid from the path "somepath/batchid=12313144/part-0000.csv"
    val temp = new File(tempDir)
    val out = new File(outDir)

    if(!temp.exists()) throw new Exception(s"path $tempDir doesn't exist")

    if(out.exists()) {
      purgeDirectory(out)
      JobLogger.log(s"cleaning out the directory ${out.getPath}")
    } else {
      out.mkdirs()
      JobLogger.log(s"creating the directory ${out.getPath}")
    }

    val fileList = recursiveListFiles(temp, ".csv")

    JobLogger.log(s"moving ${fileList.length} files to ${out.getPath}")

    fileList.foreach(file => {
      val value = regex.findFirstIn(file.getPath).getOrElse("")
      if (value.length > 1) {
        val batchid = value.substring(1, value.length() - 1)
        Files.copy(file.toPath, new File(s"${out.getPath}/report-$batchid.csv").toPath, StandardCopyOption.REPLACE_EXISTING)
      }
    })
  }
}

