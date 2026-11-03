package com.jdkshen.aijspro.mcp
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class ToolkitAtomicConcurrencyTest {
 @get:Rule val temp=TemporaryFolder()
 @Test fun independentStatusWriteIsNotBlockedByALongOutputWriter(){
  val slow=temp.newFile("output.apk");val status=temp.newFile("status.json");val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val worker=Thread{ToolkitIo.atomic(slow){entered.countDown();release.await();it.writeText("complete")}}
  worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS))
  try{ToolkitIo.atomic(status){it.writeText("running")};assertEquals("running",status.readText())}finally{release.countDown();worker.join(3000)}
  assertEquals("complete",slow.readText())
 }
 @Test fun failedReplacementKeepsPreviousBytes(){
  val file=temp.newFile("value");file.writeText("original")
  try{ToolkitIo.atomic(file){it.writeText("partial");throw java.io.IOException("fail")};fail()}catch(_:java.io.IOException){}
  assertEquals("original",file.readText())
 }
}
