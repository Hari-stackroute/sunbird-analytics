package org.ekstep.analytics.job


import org.apache.spark.SparkContext
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{col, _}
import org.ekstep.analytics.framework._
import org.ekstep.analytics.framework.util.{CommonUtil, JSONUtils, JobLogger}
import org.ekstep.analytics.util.AssessmentReportUtil
import org.sunbird.cloud.storage.conf.AppConf

import scala.collection.{Map, _}


object AssessmentMetricsJob extends optional.Application with IJob with ReportGenerator {

  implicit val className = "org.ekstep.analytics.job.AssessmentMetricsJob"

  def name(): String = "AssessmentMetricsJob"

  def main(config: String)(implicit sc: Option[SparkContext] = None) {

    JobLogger.init("Assessment Metrics")
    JobLogger.start("Assessment Job Started executing", Option(Map("config" -> config, "model" -> name)))
    val jobConfig = JSONUtils.deserialize[JobConfig](config)
    JobContext.parallelization = jobConfig.parallelization.getOrElse(10) // Default to 10

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
    val tempDir = AppConf.getConfig("assessment.metrics.temp.dir")
    val readConsistencyLevel: String = AppConf.getConfig("assessment.metrics.cassandra.input.consistency")
    val renamedDir = s"$tempDir/renamed"
    val sparkConf = sc.getConf
      .set("spark.cassandra.input.consistency.level", readConsistencyLevel)
      .set("es.scroll.size", AppConf.getConfig("es.scroll.size"))

    val spark = SparkSession.builder.config(sparkConf).getOrCreate()
    val reportDF = prepareReport(spark, loadData)
    val denormedDF = denormAssessment(spark, reportDF)
    saveReport(denormedDF, tempDir)
    JobLogger.end("AssessmentReport Generation Job completed successfully!", "SUCCESS", Option(Map("config" -> config, "model" -> name)))
  }

  /**
    * Method used to load the cassnadra table data by passing configurations
    *
    * @param spark    - Spark Sessions
    * @param settings - Cassnadra configs
    * @return
    */
  def loadData(spark: SparkSession, settings: Map[String, String]): DataFrame = {
    spark
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(settings)
      .load()
  }

  override def saveReportES(reportDF: DataFrame): Unit = ???

  /**
    * Loading the specific tables from the cassandra db.
    */
  def prepareReport(spark: SparkSession, loadData: (SparkSession, Map[String, String]) => DataFrame): DataFrame = {
    val sunbirdKeyspace = AppConf.getConfig("course.metrics.cassandra.sunbirdKeyspace")
    val sunbirdCoursesKeyspace = AppConf.getConfig("course.metrics.cassandra.sunbirdCoursesKeyspace")
    val courseBatchDF = loadData(spark, Map("table" -> "course_batch", "keyspace" -> sunbirdCoursesKeyspace))
    val userCoursesDF = loadData(spark, Map("table" -> "user_courses", "keyspace" -> sunbirdCoursesKeyspace))
    val userDF = loadData(spark, Map("table" -> "user", "keyspace" -> sunbirdKeyspace))
    val userOrgDF = loadData(spark, Map("table" -> "user_org", "keyspace" -> sunbirdKeyspace)).filter(lower(col("isdeleted")) === "false")
    val organisationDF = loadData(spark, Map("table" -> "organisation", "keyspace" -> sunbirdKeyspace))
    val locationDF = loadData(spark, Map("table" -> "location", "keyspace" -> sunbirdKeyspace))
    val externalIdentityDF = loadData(spark, Map("table" -> "usr_external_identity", "keyspace" -> sunbirdKeyspace))
    val assessmentProfileDF = loadData(spark, Map("table" -> "assessment_aggregator", "keyspace" -> sunbirdCoursesKeyspace))

    /*
    * courseBatchDF has details about the course and batch details for which we have to prepare the report
    * courseBatchDF is the primary source for the report
    * userCourseDF has details about the user details enrolled for a particular course/batch
    * */
    val userCourseDenormDF = courseBatchDF.join(userCoursesDF, userCoursesDF.col("batchid") === courseBatchDF.col("batchid") && lower(userCoursesDF.col("active")).equalTo("true"), "inner")
      .select(userCoursesDF.col("batchid"),
        col("userid"),
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
        col("userid"),
        col("locationids"),
        concat_ws(" ", col("firstname"), col("lastname")).as("username"))
    /**
      * externalIdMapDF - Filter out the external id by idType and provider and Mapping userId and externalId
      */
    val externalIdMapDF = userDF.join(externalIdentityDF, externalIdentityDF.col("idtype") === userDF.col("channel") && externalIdentityDF.col("provider") === userDF.col("channel") && externalIdentityDF.col("userid") === userDF.col("userid"), "inner")
      .select(externalIdentityDF.col("externalid"), externalIdentityDF.col("userid"))

    /*
    * userDenormDF lacks organisation details, here we are mapping each users to get the organisationids
    * */
    val userRootOrgDF = userDenormDF
      .join(userOrgDF, userOrgDF.col("userid") === userDenormDF.col("userid") && userOrgDF.col("organisationid") === userDenormDF.col("rootorgid"))
      .select(userDenormDF.col("*"), col("organisationid"))

    val userSubOrgDF = userDenormDF
      .join(userOrgDF, userOrgDF.col("userid") === userDenormDF.col("userid") && userOrgDF.col("organisationid") =!= userDenormDF.col("rootorgid"))
      .select(userDenormDF.col("*"), col("organisationid"))

    val rootOnlyOrgDF = userRootOrgDF
      .join(userSubOrgDF, Seq("userid"), "leftanti")
      .select(userRootOrgDF.col("*"))

    val userOrgDenormDF = rootOnlyOrgDF.union(userSubOrgDF)

    /**
      * Get the District name for particular user based on the location identifiers
      */
    val locationDenormDF = userOrgDenormDF
      .withColumn("exploded_location", explode(col("locationids")))
      .join(locationDF, col("exploded_location") === locationDF.col("id") && locationDF.col("type") === "district")
      .dropDuplicates(Seq("userid"))
      .select(col("name").as("district_name"), col("userid"))

    val userLocationResolvedDF = userOrgDenormDF
      .join(locationDenormDF, Seq("userid"), "left_outer")

    // Enable this below code to get only last attempted question
    val groupdedDF = Window.partitionBy("user_id", "batch_id", "course_id", "content_id").orderBy(desc("last_attempted_on"))
    val latestAssessmentDF = assessmentProfileDF.withColumn("rownum", row_number.over(groupdedDF)).where(col("rownum") === 1).drop("rownum")

    /** attempt_id
      * Compute the sum of all the worksheet contents score.
      */
    val assessmentAggDf = Window.partitionBy("user_id", "batch_id", "course_id")
    val aggregatedDF = latestAssessmentDF.withColumn("total_sum_score", sum("total_score") over assessmentAggDf)

    /**
      * Filter only valid enrolled userid for the specific courseid
      */
    val userAssessmentResolvedDF = userLocationResolvedDF.join(aggregatedDF, userLocationResolvedDF.col("userid") === aggregatedDF.col("user_id") && userLocationResolvedDF.col("batchid") === aggregatedDF.col("batch_id") && userLocationResolvedDF.col("courseid") === aggregatedDF.col("course_id"), "left_outer")
    val resolvedExternalIdDF = userAssessmentResolvedDF.join(externalIdMapDF, Seq("userid"), "left_outer")

    /*
    * Resolve organisation name from `rootorgid`
    * */
    val resolvedOrgNameDF = resolvedExternalIdDF
      .join(organisationDF, organisationDF.col("id") === resolvedExternalIdDF.col("rootorgid"), "left_outer")
      .dropDuplicates(Seq("userid"))
      .select(resolvedExternalIdDF.col("userid"), col("orgname").as("orgname_resolved"))


    /*
    * Resolve school name from `orgid`
    * */
    val resolvedSchoolNameDF = resolvedExternalIdDF
      .join(organisationDF, organisationDF.col("id") === resolvedExternalIdDF.col("organisationid"), "left_outer")
      .dropDuplicates(Seq("userid"))
      .select(resolvedExternalIdDF.col("userid"), col("orgname").as("schoolname_resolved"))


    /*
    * merge orgName and schoolName based on `userid` and calculate the course progress percentage from `progress` column which is no of content visited/read
    * */

    resolvedExternalIdDF
      .join(resolvedSchoolNameDF, Seq("userid"), "left_outer")
      .join(resolvedOrgNameDF, Seq("userid"), "left_outer")
  }

  /**
    * De-norming the assessment report - Adding content name column to the content id
    *
    * @return - Assessment denormalised dataframe
    */
  def denormAssessment(spark: SparkSession, report: DataFrame): DataFrame = {
    val contentIds = report.select(col("content_id")).rdd.map(r => r.getString(0)).collect.toList.distinct.filter(_ != null)
    val contentNameDF = AssessmentReportUtil.getContentNames(spark, contentIds)
    report.join(contentNameDF, report.col("content_id") === contentNameDF.col("identifier"), "right_outer")
      .select(col("name"),
        col("total_sum_score"), report.col("userid"), report.col("courseid"), report.col("batchid"),
        col("total_score"), report.col("maskedemail"), report.col("district_name"), report.col("maskedphone"),
        report.col("orgname_resolved"), report.col("externalid"), report.col("schoolname_resolved"), report.col("username")
      )
  }


  /**
    * This method is used to upload the report the azure cloud service.
    * TODO: Need to optimize this method.
    */
  def saveReport(reportDF: DataFrame, url: String): Unit = {
    // Save assessment report to ealstic search
    val alias_name = AppConf.getConfig("assessment.metrics.es.alias")
    val index_prefix = AppConf.getConfig("assessment.metrics.es.index.prefix")
    val index_name = AssessmentReportUtil.suffixDate(index_prefix)
    AssessmentReportUtil.saveToElastic(index_name, alias_name, reportDF)

    val result = reportDF
      .groupBy("courseid")
      .agg(collect_list("batchid").as("batchid"))

    val course_batch_list = result.collect.map(r => Map(result.columns.zip(r.toSeq): _*))
    course_batch_list.foreach(item => {
      val batchList = item.getOrElse("batchid", null).asInstanceOf[Seq[String]].distinct
      val courseId = item.getOrElse("courseid", null).toString
      batchList.foreach(batchId => {
        if (courseId != null && batchId != null) {
          val reportData = transposeDF(reportDF, courseId, batchId)
          // Save report to azure cloud storage
          AssessmentReportUtil.save(reportData, url)
        }
      })
    })
  }

  def transposeDF(reportDF: DataFrame, courseId: String, batchId: String): DataFrame = {
    // Re-shape the dataframe (Convert the content name from the row to column)
    JobLogger.log(s"Generating report for ${courseId} course and ${batchId} batch")
    val reshapedDF = reportDF.filter(col("courseid") === courseId && col("batchid") === batchId).
      groupBy("courseid", "batchid", "userid").pivot("name").agg(first("total_score"))
    reshapedDF
      .join(reportDF, Seq("courseid", "batchid", "userid"),
        "inner").select(
      reportDF.col("externalid").as("External ID"),
      reportDF.col("userid").as("User ID"),
      reportDF.col("username").as("User Name"),
      reportDF.col("maskedemail").as("Email ID"),
      reportDF.col("maskedphone").as("Mobile Number"),
      reportDF.col("orgname_resolved").as("Organisation Name"),
      reportDF.col("district_name").as("District Name"),
      reportDF.col("schoolname_resolved").as("School Name"),
      reshapedDF.col("*"), // Since we don't know the content name column so we are using col("*")
      reportDF.col("total_sum_score").as("Total Score")
    ).dropDuplicates("userid", "courseid", "batchid").drop("userid")
  }


}
