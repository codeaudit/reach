package edu.arizona.sista.assembly

import edu.arizona.sista.odin._
import edu.arizona.sista.reach.RuleReader
import edu.arizona.sista.reach.mentions.BioRelationMention
import edu.arizona.sista.reach.mentions._


trait AssemblySieve {

  // the label to be used by RelationMentions constructed during assembly
  val label = "Assembly"
  val labels = Seq(label)
  // the name of the sieve
  def name = this.getClass.getSimpleName

  // takes mentions and produces an AssemblyGraph
  def assemble(mentions:Seq[Mention]): AssemblyGraph

  def assemblyViaRules(rulesPath: String, reachOutput:Seq[Mention]):Seq[RelationMention] = {
    // read rules and initialize state with existing mentions
    val rules:String = RuleReader.readResource(rulesPath)
    val ee = ExtractorEngine(rules)
    // a subset of the mentions found by REACH, filtered for assembly
    val validMentions = reachOutput.filter(_ matches "PossibleController")
    // since we break a paper into sections, we'll need to group the mentions by doc
    // rule set only produces target RelationMentions

    val assembledMentions =
      for {
        (doc, mentionsFromReach) <- validMentions.groupBy(_.document)
        // create a new state with just the mentions from a particular doc
        // note that a doc is as granular as a section of a paper
        oldState = State(mentionsFromReach)
        // extract the assembly mentions from the subset of reach mentions
        // belonging to the same doc.
        // NOTE: Odin expects all mentions in the state to belong to the same doc!
        m <- ee.extractFrom(doc, oldState)
        // TODO: this may not be necessary
        // ensure that mention is one related to Assembly
        if m matches this.label
      } yield m.asInstanceOf[RelationMention]

    assembledMentions.filter( _ matches "Assembly" )
      .map( _.toBioMention)
      .map( _.asInstanceOf[BioRelationMention] )

    assembledMentions.toSeq
  }

  def assembleAndFilter(mentions:Seq[Mention]):AssemblyGraph = {
    // Before and After must be PossibleControllers
    // If either is an entity, it must have a PTM modification
    AssemblyGraph(Constraints.imposeAssemblyConstraints(assemble(mentions).connected), this.name)
  }
}

/**
 * Domain-specific sieve.
 * Check to see if the output of a mention is the input to another
 */
class ExactIOSieve extends AssemblySieve {

  def assemble(mentions:Seq[Mention]): AssemblyGraph = {
    // find pairs that satisfy strict IO conditions
    // input of m1 must be output of m2 OR input of m2 must be output m1
    val links:Seq[BioRelationMention] =
      for {
        m1 <- mentions
        // get output representation for m1
        m1inputs = IOResolver.getInputs(m1)
        // get output representation for m1
        m1outputs = IOResolver.getOutputs(m1)
        // compare m1's IO to the IO of every other mention
        m2 <- mentions
        // get output representation for m1
        m2inputs = IOResolver.getInputs(m2)
        // get output representation for m2
        m2outputs = IOResolver.getOutputs(m2)
        // don't link if these mentions are the same
        // TODO: strict mention equality isn't inclusive enough for this check
        // Look at representation of mentions?
        if m1 != m2
        // only yield if the strict IO constraint holds
        // all outputs of one of the mentions must be contained by the inputs of the other mention
        if m1outputs.isSubsetOf(m2inputs) || m2outputs.isSubsetOf(m1inputs)
      } yield {
        val result = (m1, m2) match {
          // all of mention 1's output serves is contained by the input to mention 2
          case (incoming, outgoing) if m1outputs.isSubsetOf(m2inputs) =>
            // the assembly link
            new BioRelationMention(labels = this.labels,
              arguments = Map(Architecture.predecessor -> Seq(incoming), Architecture.successor -> Seq(outgoing)),
              sentence = incoming.sentence,
              document = incoming.document,
              keep = true,
              foundBy = this.name)
          // all of mention 2's output serves is contained by the input to mention 1
          case (outgoing, incoming) if m2outputs.isSubsetOf(m1inputs) =>
            // the assembly link
            new BioRelationMention(labels = this.labels,
              arguments = Map(Architecture.predecessor -> Seq(incoming), Architecture.successor -> Seq(outgoing)),
              sentence = incoming.sentence,
              document = incoming.document,
              keep = true,
              foundBy = this.name)
        }
        result
      }
    AssemblyGraph(links, this.name)
  }

  override def assembleAndFilter(mentions:Seq[Mention]):AssemblyGraph = {
    // For IO sieves, "before" should be an Event
    AssemblyGraph(Constraints.beforeMustBeEvent(
      Constraints.imposeAssemblyConstraints(
        assemble(mentions).connected
      )
    ), this.name)
  }
}

/**
 * Domain-specific sieve.
 * Check to see if the output of a mention is the input to another
 */
class ApproximateIOSieve extends AssemblySieve {

  def assemble(mentions:Seq[Mention]): AssemblyGraph = {
    // find pairs that satisfy approximate IO conditions
    // input of m1 must be approximate output of m2 OR input of m2 must be approximate output m1
    // see IO.fuzzyMatch for details on meaning of "approximate"
    val links:Seq[BioRelationMention] =
      for {
        m1 <- mentions
        // get output representation for m1
        m1inputs = IOResolver.getInputs(m1)
        // get output representation for m1
        m1outputs = IOResolver.getOutputs(m1)
        // compare m1's IO to the IO of every other mention
        m2 <- mentions
        // get output representation for m1
        m2inputs = IOResolver.getInputs(m2)
        // get output representation for m2
        m2outputs = IOResolver.getOutputs(m2)
        // only yield if the approximate IO constraint holds
        if m1outputs.isFuzzySubsetOf(m2inputs) || m2outputs.isFuzzySubsetOf(m1inputs)
      } yield {
        val result = (m1, m2) match {
          // mention 2's input is coming from the output of mention 1
          case (incoming, outgoing) if m1outputs.isFuzzySubsetOf(m2inputs) =>
            // the assembly link
            new BioRelationMention(labels = this.labels,
              arguments = Map(Architecture.predecessor -> Seq(incoming), Architecture.successor -> Seq(outgoing)),
              sentence = incoming.sentence,
              document = incoming.document,
              keep = true,
              foundBy = this.name)
          // mention 1's input is coming from the output of mention 2
          case (outgoing, incoming) if m2outputs.isFuzzySubsetOf(m1inputs) =>
            // the assembly link
            new BioRelationMention(labels = this.labels,
              arguments = Map(Architecture.predecessor -> Seq(incoming), Architecture.successor -> Seq(outgoing)),
              sentence = incoming.sentence,
              document = incoming.document,
              keep = true,
              foundBy = this.name)
        }
        result
      }
    AssemblyGraph(links, this.name)
  }

  override def assembleAndFilter(mentions:Seq[Mention]):AssemblyGraph = {
    // For IO sieves, "before" should be an Event
    AssemblyGraph(Constraints.beforeMustBeEvent(
      Constraints.imposeAssemblyConstraints(
        assemble(mentions).connected
      )
    ), this.name)
  }
}

/**
 * Linguistic sieve (intra-sentence).
 * Run Odin syntactic dep rules to see if mentions are linked by a prep
 */
class PrepositionLinkSieve extends AssemblySieve {

  def assemble(mentions:Seq[Mention]): AssemblyGraph = {

    // read rules and initialize state with existing mentions
    val p = "/edu/arizona/sista/assembly/grammar/assembly.yml"
    val assembledMentions = assemblyViaRules(p, mentions)
    AssemblyGraph(assembledMentions, this.name)
  }
}

class InterSentenceLinguisticSieve extends AssemblySieve {

  def assemble(mentions:Seq[Mention]): AssemblyGraph = {
    val p = "/edu/arizona/sista/assembly/grammar/cross-sentence-assembly.yml"
    val assembledMentions = assemblyViaRules(p, mentions)
    AssemblyGraph(assembledMentions, this.name)
  }
}

// A set of constraints used to validate the output of sieves
object Constraints {

  // test if PTM
  def hasPTM(m: Mention): Boolean = m.toBioMention.modifications.exists(_.isInstanceOf[PTM])

  // Restrict "before" to Event
  // Used by IO Sieves
  def beforeMustBeEvent(ams: Seq[RelationMention]):Seq[RelationMention] = {
    for {
      am <- ams
      // size should be 1
      before = am.arguments("before").head
      if before matches "Event"
    } yield am
  }

  // constraints on assembled mentions to avoid redundancies
  // only return assembled mentions
  // where both "before" and "after" args are
  // PossibleControllers (see reach/biogrammar/taxonomy.yml)
  // (i.e. ignore mentions related to context, cell types, species, etc)
  // an assembled mention should not join the same Entity
  // (i.e. "before" & "after" should not both point to Entity e)
  def imposeAssemblyConstraints(ams: Seq[RelationMention]):Seq[RelationMention] = {
    for {
      am <- ams
      // both should be of size 1
      before = am.arguments("before").head
      after = am.arguments("after").head
      outputsOfBefore = IOResolver.getOutputs(before)
      inputsOfAfter = IOResolver.getInputs(after)
      outputsOfAfter = IOResolver.getOutputs(after)
      // only assemble things that involve PossibleControllers
      // lhs and rhs ("after") should be Events
      // entities don't transform input
      if before matches "PossibleController"
      // NOTE: some unresolved coref mentions linger in the reach output
      // ex (PMC3847091):  ... that this phosphorylation leads to its increased translocation to the cytosol and nucleus and increased binding to p53 (PMC3847091)
      if ! ( before matches "Generic_event")  && ! (after matches "Generic_event")
      // before and after can only be entities iff they have a PTM modification
      if (before matches "Event") || Constraints.hasPTM(before)
      if (after matches "Event") || Constraints.hasPTM(after)
      // ensure output of "before" != output of "after"
      if outputsOfBefore != outputsOfAfter
      // input of "after" != output of "after"
      // i.e. there should be a change of state in the IO
      if inputsOfAfter != outputsOfAfter
    } yield am
  }
}

// TODO: Add sieve to convert regs into Assembly mentions (A causes B, C leads to D, etc)

object SieveManager {
//  // The default sieve
//  class IdentitySieve extends AssemblySieve {
//    // do nothing
//    def assemble(mentions:Seq[Mention]) = AssemblyGraph(Nil, "")
//  }
  // alternative to reflection for lookup
  val lut =
    Map[String, AssemblySieve](
      "ExactIOSieve" -> new ExactIOSieve,
      "ApproximateIOSieve" -> new ApproximateIOSieve,
      "PrepositionLinkSieve" -> new PrepositionLinkSieve)
      // if the key is missing from the map, return an IdentitySieve
      //.withDefaultValue(new IdentitySieve)
}