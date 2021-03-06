// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.nio.file.Files

import com.microsoft.ml.spark.FileUtilities.File
import org.apache.commons.io.FileUtils
import org.apache.spark.ml.util.{MLReadable, MLWritable}
import org.apache.spark.ml._
import org.apache.spark.ml.param.ParamPair
import org.apache.spark.sql.DataFrame

case class TestObject[S <: PipelineStage](stage: S,
                                          fitDF: DataFrame,
                                          transDF: DataFrame,
                                          validateDF: Option[DataFrame]) {
  def this(stage: S, df: DataFrame) = {
    this(stage, df, df, None)
  }

  def this(stage: S, fitDF: DataFrame, transDF: DataFrame) = {
    this(stage, fitDF, transDF, None)
  }

}

trait FuzzingMethods {
  def compareDFs(df1: DataFrame, df2: DataFrame): Boolean = {
    df1.collect().toSet == df2.collect().toSet
  }
}

trait PyTestFuzzing[S <: PipelineStage] extends FuzzingMethods {

  def pyTestObjects(): Seq[TestObject[S]]

  val savedDatasetFolder: File = new File("???")
  // TODO make this Desired location + stage name

  def saveDatasets(): Unit = {
    // TODO implement this body
  }

  def pythonizeParam(p: ParamPair[_]): String = {
    p.param.name + "=" + p.value
    // TODO make this a valid scala to python setter converter.
    // TODO Maybe look at JsonEncode function

  }

  def pyTest(stage: S, fitPath: File, testPath: File): String = {
    val paramMap = stage.extractParamMap()
    stage match {
      case t: Transformer => ???
      //s"transformer = ${stage.getClass.getName.split(".").last}()" +
      //  s""
      //TODO fill this in along with estimator case
      // import stuff
      // load fitting and testing dfs from paths
      // instantiatie the python wrapper with parameters gotten from stage's param map
      // pyStage.transform
      // transformer test logic here
      case e: Estimator[_] => ??? // estimator test logic here
      case _ => throw new MatchError(s"Stage $stage should be a transformer or estimator")
    }
  }

  def getPyTests(): Seq[String] = {
    pyTestObjects().zipWithIndex.map { case (req, i) =>
      pyTest(req.stage,
        new File(new File(savedDatasetFolder, i.toString), "fit"),
        new File(new File(savedDatasetFolder, i.toString), "transform"))
    }
  }

}

trait ExperimentFuzzing[S <: PipelineStage] extends FuzzingMethods {

  def experimentTestObjects(): Seq[TestObject[S]]

  def runExperiment(s: S, fittingDF: DataFrame, transformingDF: DataFrame): DataFrame = {
    s match {
      case t: Transformer =>
        t.transform(transformingDF)
      case e: Estimator[_] =>
        e.fit(fittingDF).transform(transformingDF)
      case _ => throw new MatchError(s"$s is not a Transformer or Estimator")
    }
  }

  def validateExperiments(): Unit = {
    experimentTestObjects().foreach { req =>
      val res = runExperiment(req.stage, req.fitDF, req.transDF)
      req.validateDF match {
        case Some(vdf) => compareDFs(res, vdf)
        case None => ()
      }
    }
  }

}

trait SerializationFuzzing[S <: PipelineStage with MLWritable] extends FuzzingMethods {
  def serializationTestObjects(): Seq[TestObject[S]]

  def reader: MLReadable[_]

  def modelReader: MLReadable[_]

  val savePath: String = Files.createTempDirectory("SavedModels-").toString

  val ignoreEstimators: Boolean = false

  private def testRoundTripHelper(path: String,
                                  stage: PipelineStage with MLWritable,
                                  reader: MLReadable[_],
                                  fitDF: DataFrame, transDF: DataFrame): Unit = {
    try {
      stage.write.overwrite().save(path)
      val loadedStage = reader.load(path)
      (stage, loadedStage) match {
        case (e1: Estimator[_], e2: Estimator[_]) =>
          assert(compareDFs(e1.fit(fitDF).transform(transDF), e2.fit(fitDF).transform(transDF)))
        case (t1: Transformer, t2: Transformer) =>
          assert(compareDFs(t1.transform(transDF), t2.transform(transDF)))
        case _ => throw new IllegalArgumentException(s"$stage and $loadedStage do not have proper types")
      }
      ()
    } finally {
      FileUtils.forceDelete(new File(path))
      ()
    }
  }

  def testRoundTrip(): Unit = {
    serializationTestObjects().foreach { req =>
      val fitStage = req.stage match {
        case stage: Estimator[_] =>
          if (!ignoreEstimators) {
            testRoundTripHelper(savePath + "/stage", stage, reader, req.fitDF, req.transDF)
          }
          stage.fit(req.fitDF).asInstanceOf[PipelineStage with MLWritable]
        case stage: Transformer => stage
        case s => throw new IllegalArgumentException(s"$s does not have correct type")
      }
      testRoundTripHelper(savePath + "/fitStage", fitStage, modelReader, req.transDF, req.transDF)

      val pipe = new Pipeline().setStages(Array(req.stage.asInstanceOf[PipelineStage]))
      if (!ignoreEstimators) {
        testRoundTripHelper(savePath + "/pipe", pipe, Pipeline, req.fitDF, req.transDF)
      }
      val fitPipe = pipe.fit(req.fitDF)
      testRoundTripHelper(savePath + "/fitPipe", fitPipe, PipelineModel, req.transDF, req.transDF)
    }
  }

}

trait Fuzzing[S <: PipelineStage with MLWritable] extends PyTestFuzzing[S]
  with SerializationFuzzing[S] with ExperimentFuzzing[S] {

  def testObjects(): Seq[TestObject[S]]

  def pyTestObjects(): Seq[TestObject[S]] = testObjects()

  def serializationTestObjects(): Seq[TestObject[S]] = testObjects()

  def experimentTestObjects(): Seq[TestObject[S]] = testObjects()

}

trait TransformerFuzzing[S <: PipelineStage with MLWritable] extends Fuzzing[S] {

  override val ignoreEstimators: Boolean = true

  override def modelReader: MLReadable[_] = reader

}

trait EstimatorFuzzing[S <: PipelineStage with MLWritable] extends Fuzzing[S]
