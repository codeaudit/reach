package edu.arizona.sista.reach

import org.scalatest.{Matchers, FlatSpec}
import TestUtils._
import edu.arizona.sista.reach.grounding2._

/**
  * Unit tests to ensure the in-memory KB is working for grounding.
  *   Written by: Tom Hicks. 10/26/2015.
  *   Last Modified: Update for IMKB has species argument.
  */
class TestIMKB extends FlatSpec with Matchers {

  // Tests of non-speciated (2-column) knowledge base
  val imkb2 = new InMemoryKB(
    new KBMetaInfo("http://identifiers.org/uazclu/", "UAZ", "MIR:00000000"),
    "uniprot-subcellular-locations.tsv")

  "InMemoryKB COL-2" should "lookup on IMKB from COL-2 TSV file" in {
    (imkb2.lookup("NOT-IN-KB").isEmpty) should be (true)
    (imkb2.lookup("dendrite").isDefined) should be (true)
  }


  // Tests of speciated (3-column) knowledge base
  val imkbPF = new InMemoryKB(
    new KBMetaInfo("http://identifiers.org/uazclu/", "UAZ", "MIR:00000000"),
    "ProteinFamilies.tsv.gz", true)

  // test lookups directly in IMKB (remember all keys must be lowercased!)
  "InMemoryKB COL-3" should "lookup on IMKB from COL-3 gzipped TSV file" in {
    (imkbPF.lookup("NOT-IN-KB").isDefined) should be (false) // not in KB
    (imkbPF.lookup("PTHR21244").isDefined) should be (false) // uppercase
    (imkbPF.lookup("not-in-kb").isDefined) should be (false) // not it KB
    (imkbPF.lookup("pthr21244").isDefined) should be (false) // has a species
    (imkbPF.lookup("hk").isDefined) should be (false)        // has a species
  }

  "InMemoryKB COL-3" should "lookupByASpecies on IMKB from COL-3 gzipped TSV file" in {
    (imkbPF.lookupByASpecies("NOT-IN-KB", "human").isDefined) should be (false) // not in KB
    (imkbPF.lookupByASpecies("PTHR21244", "human").isDefined) should be (false) // uppercase
    (imkbPF.lookupByASpecies("pthr21244", "human").isDefined) should be (true)
    (imkbPF.lookupByASpecies("pthr21244", "HUMAN").isDefined) should be (true)
    (imkbPF.lookupByASpecies("pthr21244", "mouse").isDefined) should be (true)
    (imkbPF.lookupByASpecies("pthr21244", "aardvark").isDefined) should be (false) // not in KB
    (imkbPF.lookupByASpecies("hk", "saccharomyces cerevisiae").isDefined) should be (true)
  }

  "InMemoryKB COL-3" should "lookupBySpecies on IMKB from COL-3 gzipped TSV file" in {
    (imkbPF.lookupBySpecies("pthr21244", Set("aardvark")).isDefined) should be (false)
    (imkbPF.lookupBySpecies("pthr21244", Set("human")).isDefined) should be (true)
    val pt = imkbPF.lookupBySpecies("pthr21244", Set("human", "mouse"))
    (pt.isDefined) should be (true)
    (pt.get.size == 2) should be (true)
    (imkbPF.lookupBySpecies("pthr21244", Set("human","mouse","gorilla")).isDefined) should be (true)
    val pt2 = (imkbPF.lookupBySpecies("pthr21244", Set("human", "mouse","gorilla")))
    (pt2.isDefined) should be (true)
    (pt2.get.size == 2) should be (true)
    (imkbPF.lookupBySpecies("hk", Set("human", "mouse")).isDefined) should be (false)
    (imkbPF.lookupBySpecies("hk", Set("saccharomyces cerevisiae", "ant")).isDefined) should be (true)
    (imkbPF.lookupBySpecies("hk", Set("ant", "saccharomyces cerevisiae")).isDefined) should be (true)
    (imkbPF.lookupBySpecies(
      "hk", Set("ant", "saccharomyces cerevisiae")).get.size == 1) should be (true)
  }

  "InMemoryKB COL-3" should "lookupHuman on IMKB from COL-3 gzipped TSV file" in {
    (imkbPF.lookupHuman("hk").isDefined) should be (false) // yeast only
    (imkbPF.lookupHuman("PTHR21244").isDefined) should be (false)
    (imkbPF.lookupHuman("pthr21244").isDefined) should be (true)
  }


  // Tests of AZ Failsafe KB
  val kbAZ = new AzFailsafeKBML
  "AZ Failsafe KB" should "return new UAZ ID for new resolve, then repeat it" in {
    val x3 = kbAZ.resolve("XXX")
    (x3.isDefined) should be (true)
    val xxx = x3.get
    (kbAZ.resolve("XXX").get.id == xxx.id) should be (true)
    (kbAZ.resolve("xxx").get.id == xxx.id) should be (true)
    (kbAZ.resolve("XXXX").get.id == xxx.id) should be (false) // 4 Xs
    (kbAZ.resolve("xxxx").get.id == xxx.id) should be (false) // 4 xs
  }

  "AZ Failsafe KB" should "return new UAZ ID for new resolve and resolveHuman" in {
    val x3 = kbAZ.resolve("XXX")
    (x3.isDefined) should be (true)
    val xxx = x3.get
    (kbAZ.resolveHuman("XXX").isDefined) should be (true)
    (kbAZ.resolveHuman("XXX").get.id == xxx.id) should be (true)
    (kbAZ.resolveHuman("xxx").isDefined) should be (true)
    (kbAZ.resolveHuman("xxx").get.id == xxx.id) should be (true)
  }

  "AZ Failsafe KB" should "return new UAZ ID for new resolve and resolveByASpecies" in {
    val x3 = kbAZ.resolve("XXX")
    (x3.isDefined) should be (true)
    val xxx = x3.get
    (kbAZ.resolveByASpecies("XXX", "giraffe").isDefined) should be (true)
    (kbAZ.resolveByASpecies("XXX", "giraffe").get.id == xxx.id) should be (true)
    (kbAZ.resolveByASpecies("xxx", "giraffe").isDefined) should be (true)
    (kbAZ.resolveByASpecies("xxx", "giraffe").get.id == xxx.id) should be (true)
  }

  "AZ Failsafe KB" should "return new UAZ ID for new resolve and resolveBySpecies" in {
    val x3 = kbAZ.resolve("XXX")
    (x3.isDefined) should be (true)
    val xxx = x3.get
    (kbAZ.resolveBySpecies("XXX", Set("giraffe")).isDefined) should be (true)
    (kbAZ.resolveBySpecies("XXX", Set("giraffe")).get.head.id == xxx.id) should be (true)
    (kbAZ.resolveBySpecies("XXX", Set("human", "mouse")).isDefined) should be (true)
    (kbAZ.resolveBySpecies("XXX", Set("human", "mouse")).get.head.id == xxx.id) should be (true)
    (kbAZ.resolveBySpecies("xxx", Set("giraffe")).isDefined) should be (true)
    (kbAZ.resolveBySpecies("xxx", Set("giraffe")).get.head.id == xxx.id) should be (true)
    (kbAZ.resolveBySpecies("xxx", Set("human", "mouse")).isDefined) should be (true)
    (kbAZ.resolveBySpecies("xxx", Set("human", "mouse")).get.head.id == xxx.id) should be (true)
  }

}
