// scalastyle:off

package mainapp

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.clustering._
import org.apache.spark.mllib.linalg.{Vector => LinAlgVector}
import org.apache.spark.rdd.RDD

import excel2rdd.Excel2RDD

object SpCluster {
  def main(cmdLineArgs: Array[String]) : Unit = {

    val sparkConf = new SparkConf().setMaster("local[2]").setAppName("Clustering")
    sparkConf.set("spark.ui.enabled", "false")

    val sc = new SparkContext(sparkConf)

    val excelXlsx = new Excel2RDD("/tmp/FRBNY-SCE-Housing-Module-Public-Microdata-Complete.xlsx")

    excelXlsx.open()
    val parsedData = excelXlsx.convertExcelSpreadsh2RDD("Data", sc)
    excelXlsx.close()

    parsedData.saveAsTextFile("/tmp/filtered_copy_directory")

    if (!validateRDDForKMeans(parsedData)) {
      System.err.println("There are vectors inside the RDD which have different dimensions.\n" +
                         "All vectors must have the same dimensions.\nAborting.")
      sc.stop()
      System.exit(1)
    }

    val clusters = trainKMeans(parsedData)

    // print the centers of the clusters returned by the training of KMeans
    for { i <- 0 until clusters.k } {
      println(clusters.clusterCenters(i).toJson)
    }

    // Evaluate clustering by computing Within Set Sum of Squared Errors
    val WSSSE = clusters.computeCost(parsedData)
    println("Within Set Sum of Squared Errors = " + WSSSE)
    // Save and load model
    clusters.save(sc, "/tmp/mymodel.kmeans")
    val sameModel = KMeansModel.load(sc, "/tmp/mymodel.kmeans")

    sc.stop()
  }

  def minMaxVectorInRdd(rdd: RDD[LinAlgVector]): (Int, Int) = {
    var min = Int.MaxValue
    var max = Int.MinValue

    rdd.collect().foreach(v => {
        val v_sz = v.size
        if (v_sz < min) {
          min = v_sz
        } else if (v_sz > max) {
          max = v_sz
        }
      }
    )

    (min, max)
  }

  def validateRDDForKMeans(rdd: RDD[LinAlgVector]): Boolean = {

    // Avoid an exception because the vectors inside the RDD have different size
    // (as of the current version of Spark 1.6.1 as of April 18, 2016). This issue
    // appears because the ETL on the raw text file given, happens to generate vectors
    // with different dimensions (this is an issue with the input raw text file given,
    // as mentioned above in acquireRDD(...)).
    //
    // Exception ... org.apache.spark.SparkException: ...: java.lang.IllegalArgumentException: requirement failed
    //    at scala.Predef$.require(Predef.scala:221)
    //    at org.apache.spark.mllib.util.MLUtils$.fastSquaredDistance(MLUtils.scala:330)
    //    at org.apache.spark.mllib.clustering.KMeans$.fastSquaredDistance(KMeans.scala:595)
    //    at org.apache.spark.mllib.clustering.KMeans$$anonfun$findClosest$1.apply(KMeans.scala:569)
    //    at org.apache.spark.mllib.clustering.KMeans$$anonfun$findClosest$1.apply(KMeans.scala:563)
    //
    // The lines at MLUtils.scala:330 in the stack trace of the exception are:
    //   329       val n = v1.size
    //   330       require(v2.size == n)
    // so vectors need to be the same size (in this case, it happens that one of the vectors is
    // the calculated center of a KMeans cluster).

    // get the minimum an maximum sizes for all vectors inside the RDD
    val (min_size, max_size) = minMaxVectorInRdd(rdd)

    (min_size == max_size)
  }

  def trainKMeans(rdd: RDD[LinAlgVector], numClusters: Int = 5, numIterations: Int = 30):
      KMeansModel = {

    val clusters = KMeans.train(rdd, numClusters, numIterations)
    // println("Clusters found. Class " + clusters.getClass.getName)
    clusters
  }

}
