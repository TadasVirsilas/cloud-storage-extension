package com.exasol.cloudetl.scriptclasses

import java.util.UUID

import scala.collection.mutable.ListBuffer

import com.exasol.ExaIterator
import com.exasol.ExaMetadata
import com.exasol.cloudetl.bucket.Bucket
import com.exasol.cloudetl.data.ExaColumnInfo
import com.exasol.cloudetl.data.Row
import com.exasol.cloudetl.parquet.ParquetRowWriter
import com.exasol.cloudetl.parquet.ParquetWriteOptions
import com.exasol.cloudetl.util.SchemaUtil

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.Path

@SuppressWarnings(Array("org.wartremover.warts.Var"))
object ExportTable extends LazyLogging {

  def run(meta: ExaMetadata, iter: ExaIterator): Unit = {
    val bucketPath = iter.getString(0)
    val params = Bucket.keyValueStringToMap(iter.getString(1))
    val bucket = Bucket(params)

    val srcColumnNames = iter.getString(2).split("\\.")
    val firstColumnIdx = 3
    val columns = getColumns(meta, srcColumnNames, firstColumnIdx)

    val parquetFilename = generateParquetFilename(meta)
    val path = new Path(bucketPath, parquetFilename)
    val messageType = SchemaUtil.createParquetMessageType(columns, "exasol_export_schema")
    val options = ParquetWriteOptions(params)
    val writer = ParquetRowWriter(path, bucket.getConfiguration(), messageType, options)

    val nodeId = meta.getNodeId
    val vmId = meta.getVmId
    logger.info(s"Starting export from node: $nodeId, vm: $vmId.")

    var count = 0;
    do {
      val row = getRow(iter, firstColumnIdx, columns)
      writer.write(row)
      count = count + 1
    } while (iter.next())

    writer.close()

    logger.info(s"Exported '$count' records from node: $nodeId, vm: $vmId.")
  }

  private[this] def generateParquetFilename(meta: ExaMetadata): String = {
    val nodeId = meta.getNodeId
    val vmId = meta.getVmId
    val uuidStr = UUID.randomUUID.toString.replaceAll("-", "")
    s"exa_export_${nodeId}_${vmId}_$uuidStr.parquet"
  }

  private[this] def getRow(iter: ExaIterator, startIdx: Int, columns: Seq[ExaColumnInfo]): Row = {
    val vals = columns.zipWithIndex.map {
      case (col, idx) =>
        SchemaUtil.exaColumnToValue(iter, startIdx + idx, col)
    }
    Row(values = vals)
  }

  /**
   * Creates a sequence of [[ExaColumnInfo]] columns using an Exasol
   * [[ExaMetadata]] input column methods.
   *
   * Set the name of the column using `srcColumnNames` parameter.
   * Additionally, set the precision, scale and length using
   * corresponding functions on Exasol metadata for input columns.
   *
   * @param meta An Exasol [[ExaMetadata]] metadata
   * @param srcColumnNames A sequence of column names per each input
   *        column in metadata
   * @param startIdx A starting integer index to reference input column
   * @return A sequence of [[ExaColumnInfo]] columns
   */
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private[this] def getColumns(
    meta: ExaMetadata,
    srcColumnNames: Seq[String],
    startIdx: Int
  ): Seq[ExaColumnInfo] = {
    val totalColumnCnt = meta.getInputColumnCount.toInt
    val columns = ListBuffer[ExaColumnInfo]()

    for { idx <- startIdx until totalColumnCnt } columns.append(
      ExaColumnInfo(
        name = srcColumnNames(idx - startIdx),
        `type` = meta.getInputColumnType(idx),
        precision = meta.getInputColumnPrecision(idx).toInt,
        scale = meta.getInputColumnScale(idx).toInt,
        length = meta.getInputColumnLength(idx).toInt,
        isNullable = true
      )
    )

    columns.toSeq
  }

}
