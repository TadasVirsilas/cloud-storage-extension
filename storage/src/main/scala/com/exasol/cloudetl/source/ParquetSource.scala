package com.exasol.cloudetl.source

import scala.util.control.NonFatal

import com.exasol.cloudetl.data.Row
import com.exasol.cloudetl.parquet.RowReadSupport

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.hadoop.metadata.ParquetMetadata
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.MessageType

/**
 * A Parquet source that can read parquet formatted files from Hadoop
 * compatible storage systems.
 */
final case class ParquetSource(
  override val path: Path,
  override val conf: Configuration,
  override val fileSystem: FileSystem
) extends Source
    with LazyLogging {

  private var recordReader = createReader()

  /** @inheritdoc */
  override def stream(): Iterator[Row] =
    Iterator.continually(recordReader.read).takeWhile(_ != null)

  private[this] def createReader(): ParquetReader[Row] = {
    val newConf = new Configuration(conf)
    try {
      getSchema().foreach { schema =>
        newConf.set(ReadSupport.PARQUET_READ_SCHEMA, schema.toString)
      }
      ParquetReader.builder(new RowReadSupport, path).withConf(newConf).build()
    } catch {
      case NonFatal(exception) =>
        logger.error(s"Could not create parquet reader for path: $path", exception)
        throw exception
    }
  }

  def getSchema(): Option[MessageType] = {
    val footers = getFooters()
    if (footers.isEmpty) {
      logger.error(s"Could not read parquet metadata from paths: $path")
      throw new RuntimeException("Parquet footers are empty!")
    }
    footers.headOption.map(_.getFileMetaData().getSchema())
  }

  private[this] def getFooters(): Seq[ParquetMetadata] =
    fileSystem.listStatus(path).toList.map { status =>
      val reader = ParquetFileReader.open(HadoopInputFile.fromStatus(status, conf))
      try {
        reader.getFooter()
      } finally {
        reader.close()
      }
    }

  override def close(): Unit =
    if (recordReader != null) {
      try {
        recordReader.close()
      } finally {
        recordReader = null
      }
    }

}
