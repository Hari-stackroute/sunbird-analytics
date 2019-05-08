package org.ekstep.analytics.job

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.ekstep.analytics.model.SparkSpec
import org.scalamock.scalatest.MockFactory
import scala.collection.Map
import org.apache.spark.sql.functions._

class TestCourseMetricsJob extends SparkSpec(null) with MockFactory {
  var spark: SparkSession = _
  var courseBatchDF: DataFrame = _
  var userCoursesDF: DataFrame = _
  var userDF: DataFrame = _
  var locationDF: DataFrame = _
  var orgDF: DataFrame = _
  var userOrgDF: DataFrame = _
  var reporterMock: ReportGenerator = mock[ReportGenerator]

  override def beforeAll(): Unit = {
    super.beforeAll()
    spark = SparkSession.builder.config(sc.getConf).getOrCreate()

    /*
     * Data created with 31 active batch from batchid = 1000 - 1031
     * */
    courseBatchDF = spark
      .read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .load("src/test/resources/course-metrics-updater/courseBatchTable.csv")

    /*
     * Data created with 35 participants mapped to only batch from 1001 - 1010 (10), so report
     * should be created for these 10 batch (1001 - 1010) and 34 participants (1 user is not active in the course)
     * and along with 5 existing users from 31-35 has been subscribed to another batch 1003-1007 also
     * */
    userCoursesDF = spark
      .read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .load("src/test/resources/course-metrics-updater/userCoursesTable.csv")

    /*
     * This has users 30 from user001 - user030
     * */
    userDF = spark.read.json("src/test/resources/course-metrics-updater/userTable.json")

    /*
     * This has 30 unique location
     * */
    locationDF = spark.read.format("com.databricks.spark.csv").option("header", "true").load("src/test/resources/course-metrics-updater/locationTable.csv")

    /*
     * There are 8 organisation added to the data, which can be mapped to `rootOrgId` in user table
     * and `organisationId` in userOrg table
     * */
    orgDF = spark
      .read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .load("src/test/resources/course-metrics-updater/orgTable.csv")

    /*
     * Each user is mapped to organisation table from any of 8 organisation
     * */
    userOrgDF = spark
      .read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .load("src/test/resources/course-metrics-updater/userOrgTable.csv")
  }


  "TestUpdateCourseMetrics" should "generate reports" in {

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "course_batch", "keyspace" -> "sunbird"))
      .returning(courseBatchDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_courses", "keyspace" -> "sunbird"))
      .returning(userCoursesDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user", "keyspace" -> "sunbird"))
      .returning(userDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_org", "keyspace" -> "sunbird"))
      .returning(userOrgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "organisation", "keyspace" -> "sunbird"))
      .returning(orgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "location", "keyspace" -> "sunbird"))
      .returning(locationDF).atLeastOnce()

    val reportDF = CourseMetricsJob.prepareReport(spark, reporterMock.loadData)

    assert(reportDF.count == 34)
    assert(reportDF.groupBy(col("batchid")).count().count() == 10)

    val reportData = reportDF
      .groupBy(col("batchid"))
      .count()
      .collect()

    assert(reportData.filter(row => row.getString(0) == "1001").head.getLong(1) == 2)
    assert(reportData.filter(row => row.getString(0) == "1002").head.getLong(1) == 3)
    assert(reportData.filter(row => row.getString(0) == "1003").head.getLong(1) == 4)
    assert(reportData.filter(row => row.getString(0) == "1004").head.getLong(1) == 4)
    assert(reportData.filter(row => row.getString(0) == "1005").head.getLong(1) == 4)
    assert(reportData.filter(row => row.getString(0) == "1006").head.getLong(1) == 4)
    assert(reportData.filter(row => row.getString(0) == "1007").head.getLong(1) == 4)
    assert(reportData.filter(row => row.getString(0) == "1008").head.getLong(1) == 3)
    assert(reportData.filter(row => row.getString(0) == "1009").head.getLong(1) == 3)
    assert(reportData.filter(row => row.getString(0) == "1010").head.getLong(1) == 3)
  }

  it should "should calculate the progress" in {
    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "course_batch", "keyspace" -> "sunbird"))
      .returning(courseBatchDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_courses", "keyspace" -> "sunbird"))
      .returning(userCoursesDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user", "keyspace" -> "sunbird"))
      .returning(userDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_org", "keyspace" -> "sunbird"))
      .returning(userOrgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "organisation", "keyspace" -> "sunbird"))
      .returning(orgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "location", "keyspace" -> "sunbird"))
      .returning(locationDF).atLeastOnce()

    val reportDF = CourseMetricsJob.prepareReport(spark, reporterMock.loadData)

    //sampling report
    val data1 = reportDF
      .select("course_completion")
      .where(col("batchid") === "1007" and col("userid") === "user017")
      .collect()

    assert(data1.head.getDouble(0) == 65)

    val data2 = reportDF
      .select("course_completion")
      .where(col("batchid") === "1009" and col("userid") === "user019")
      .collect()

    assert(data2.head.get(0) == 100.0)

    val districtName = reportDF
      .select("district_name")
      .where(col("batchid") === "1006" and col("userid") === "user026")
      .collect()

    assert(districtName.head.get(0) == "GULBARGA")
  }

  it should "should round course progress to 100 when it is greater than 100" in {
    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "course_batch", "keyspace" -> "sunbird"))
      .returning(courseBatchDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_courses", "keyspace" -> "sunbird"))
      .returning(userCoursesDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user", "keyspace" -> "sunbird"))
      .returning(userDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_org", "keyspace" -> "sunbird"))
      .returning(userOrgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "organisation", "keyspace" -> "sunbird"))
      .returning(orgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "location", "keyspace" -> "sunbird"))
      .returning(locationDF).atLeastOnce()

    val reportDF = CourseMetricsJob.prepareReport(spark, reporterMock.loadData)

    val data1 = reportDF
      .select("course_completion")
      .where(col("batchid") === "1006" and col("userid") === "user005")
      .collect()

    assert(data1.head.getDouble(0) == 100)

    val data2 = reportDF
      .select("course_completion")
      .where(col("batchid") === "1005" and col("userid") === "user004")
      .collect()

    assert(data2.head.getDouble(0) == 100)
  }

  it should "[Issue SB-12141] report should have 1 record for users mapped to two organisation (root and suborg)" in {
    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "course_batch", "keyspace" -> "sunbird"))
      .returning(courseBatchDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_courses", "keyspace" -> "sunbird"))
      .returning(userCoursesDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user", "keyspace" -> "sunbird"))
      .returning(userDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "user_org", "keyspace" -> "sunbird"))
      .returning(userOrgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "organisation", "keyspace" -> "sunbird"))
      .returning(orgDF).atLeastOnce()

    (reporterMock.loadData _)
      .expects(spark, Map("table" -> "location", "keyspace" -> "sunbird"))
      .returning(locationDF).atLeastOnce()

    val reportDF = CourseMetricsJob.prepareReport(spark, reporterMock.loadData)

    val data1 = reportDF
      .where(col("batchid") === "1003" and col("userid") === "user013")
      .count()

    assert(data1 == 1)
  }
}