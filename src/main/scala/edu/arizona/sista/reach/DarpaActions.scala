package edu.arizona.sista.reach

import edu.arizona.sista.odin._
import edu.arizona.sista.reach.mentions._
import edu.arizona.sista.struct.{ DirectedGraph }

class DarpaActions extends Actions {

  import DarpaActions._

  /** Converts mentions to biomentions.
    * They are returned as mentions but they are biomentions with grounding, modifications, etc
    */
  def mkBioMention(mentions: Seq[Mention], state: State): Seq[Mention] =
    mentions.map(_.toBioMention)

  /** Unpacks RelationMentions into its arguments. A new BioTextBoundMention
    * will be created for each argument with the labels of the original RelationMention.
    * This is relying on Odin's behavior of assigning the same label of the RelationMention
    * to its arguments captured with a pattern (not mention captures).
    * This is required for RelationMentions whose arguments are used directly
    * by subsequent rules.
    * WARNING This method only handles RelationMentions. Other types of Mentions are deleted.
    */
  def unpackRelations(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case rel: RelationMention => for {
      (k, v) <- rel.arguments
      m <- v
    } yield m.toBioMention
    case _ => Nil
  }

  val mkEntities: Action = unpackRelations

  /** This action handles the creation of mentions from labels generated by the NER system.
    * Rules that use this action should run in an iteration following and rules recognizing
    * "custom" entities. This action will only create mentions if no other mentions overlap
    * with a NER label sequence.
    */
  def mkNERMentions(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap { m =>
      val candidates = state.mentionsFor(m.sentence, m.tokenInterval.toSeq)
      // do any candidates overlap the mention?
      val overlap = candidates.exists(_.tokenInterval.overlaps(m.tokenInterval))
      if (overlap) None else Some(m.toBioMention)
    }
  }

  /** This action gets RelationMentions that represents a PTM,
    * and attaches the modification to the target entity in place.
    * This action modifies mentions in-place. This action always returns
    * Nil, it assumes that the arguments are already in the state.
    */
  def storePTM(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach {
      case ptm: RelationMention if ptm matches "PTM" =>
        // convert first relation("entity") into BioMention
        val bioMention = ptm.arguments("entity").head.toBioMention
        // retrieve optional first relation("site")
        val site = ptm.arguments.get("site").map(_.head)
        // retrieves first relation("mod")
        // this is the TextBoundMention for the ModifcationTrigger
        val evidence = ptm.arguments("mod").head
        // assigns label from mod
        val label = getModificationLabel(evidence.text)
        // if label is not unknown then add PTM modification to entity in-place
        if (label != "UNKNOWN") bioMention.modifications += PTM(label, Some(evidence), site)
      case _ => ()
    }
    // this action never returns anything
    // mutates mentions in-place
    // :(
    Nil
  }

  /** Gets RelationMentions that represent an EventSite,
    * and attaches the site to the corresponding entities in-place.
    * Later, if these entities are matched as participants in an event,
    * these sites will be "promoted" to that event and removed from the entity
    * (see siteSniffer for details)
    * This action always returns Nil and assumes that the arguments are already
    * in the state.
    */
  def storeEventSite(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach {
      case es: RelationMention if es matches "EventSite" =>
        // convert each relation("entity") into BioMention
        val bioMentions = es.arguments("entity").map(_.toBioMention)
        // retrieves all the captured sites
        val sites = es.arguments("site")
        // add all sites to each entity
        for {
          b <- bioMentions
          s <- sites
        } b.modifications += EventSite(site = s)
      case _ => ()
    }
    Nil
  }

  /** Gets RelationMentions that represent a Mutant,
    * and attaches the mutation to the corresponding event in-place.
    * This action always returns Nil and assumes that the arguments are already
    * in the state.
    */
  def storeMutants(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach {
      case m: RelationMention if m matches "Mutant" =>
        val bioMention = m.arguments("entity").head.toBioMention
        val mutants = m.arguments("mutant")
        mutants foreach { mutant =>
          bioMention.modifications += Mutant(evidence = mutant, foundBy = m.foundBy)
        }
    }
    Nil
  }

  /** Gets a sequence of mentions that are candidates for becoming Ubiquitination
    * events and filters out the ones that have ubiquitin as a theme, since
    * a ubiquitin can't be ubiquitinated. Events that have ubiquitin as a cause
    * are also filtered out.
    */
  def mkUbiquitination(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val filteredMentions = mentions.filterNot { ev =>
      // Only keep mentions that don't have ubiquitin as a theme
      ev.arguments("theme").exists(_.text.toLowerCase.contains("ubiq")) ||
      // mention shouldn't have ubiquitin as a cause either, if there is a cause
      ev.arguments.get("cause").map(_.exists(_.text.toLowerCase.contains("ubiq"))).getOrElse(false)
    }
    // return biomentions
    filteredMentions.map(_.toBioMention)
  }


  def mkRegulation(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    // iterate over mentions giving preference to mentions that have an event controller
    mention <- sortMentionsByController(mentions)
    // controller/controlled paths shouldn't overlap.
    // NOTE this needs to be done on mentions coming directly from Odin
    if !hasSynPathOverlap(mention)
    // switch label if needed based on negations
    regulation = removeDummy(switchLabel(mention.toBioMention))
    // If there the Mention has both a controller and controlled, they should be distinct
    if hasDistinctControllerControlled(regulation)
  } yield {
    val controllerOption = regulation.arguments.get("controller")
    // if no controller then we are done
    if (controllerOption.isEmpty) regulation
    else {
      // assuming one controller only
      val controller = controllerOption.get.head
      // if controller is a physical entity then we are done
      if (controller matches "Entity") regulation
      else if (controller matches "SimpleEvent") {
        // convert controller event into modified physical entity
        val trigger = regulation.asInstanceOf[BioEventMention].trigger
        val newController = convertEventToEntity(controller.toBioMention.asInstanceOf[BioEventMention])
        // if for some reason the event couldn't be converted
        // just return the original mention
        if (newController.isEmpty) regulation
        else {
          // return a new event with the converted controller
          new BioEventMention(
            regulation.labels,
            trigger,
            regulation.arguments.updated("controller", Seq(newController.get)),
            regulation.sentence,
            regulation.document,
            regulation.keep,
            regulation.foundBy)
        }
      }
      // if it didn't match any case, return regulation unmodified
      else regulation
    }
  }

  def mkActivation(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    // Prefer Activations with SimpleEvents as the controller
    mention <- preferSimpleEventControllers(mentions)
    // controller/controlled paths shouldn't overlap.
    // NOTE this needs to be done on mentions coming directly from Odin
    if !hasSynPathOverlap(mention)
    // switch label if needed based on negations
    activation = removeDummy(switchLabel(mention.toBioMention))
    // retrieve regulations that overlap this mention
    regs = state.mentionsFor(activation.sentence, activation.tokenInterval, "Regulation")
    // Don't report an Activation if an intersecting Regulation has been detected
    // or if the Activation has no controller
    // or if it's controller and controlled are not distinct
    if regs.isEmpty && hasController(activation) && hasDistinctControllerControlled(activation)
  } yield activation.arguments.get("controller") match {
    // if activation has entity controller, return activation unmodified
    case Some(Seq(controller)) if controller.matches("Entity") => activation
    // activation has event controller
    case Some(Seq(controller)) =>
      val trigger = activation.asInstanceOf[BioEventMention].trigger
      val bioEventController = controller.toBioMention.asInstanceOf[BioEventMention]
      val entityController = convertEventToEntity(bioEventController)
      // if controller can't be converted to entity, return activation unmodified
      if (entityController.isEmpty) activation
      // return new activation with the event controller replaced by the entity controller
      else new BioEventMention(
        activation.labels,
        trigger,
        activation.arguments.updated("controller", Seq(entityController.get)),
        activation.sentence,
        activation.document,
        activation.keep,
        activation.foundBy)
  }

  def mkBinding(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if m.matches("Binding") =>
      // themes in a subject position
      val theme1s = m.arguments.getOrElse("theme1", Nil).map(_.toBioMention)
      // themes in an object position
      val theme2s = m.arguments.getOrElse("theme2", Nil).map(_.toBioMention)
      (theme1s, theme2s) match {
        case (t1s, Nil) if t1s.length > 1 => mkBindingsFromPairs(t1s.combinations(2).toList, m)
        case (Nil, t2s) if t2s.length > 1 => mkBindingsFromPairs(t2s.combinations(2).toList, m)
        case (gen1, Nil) if gen1.exists(t => t matches "Generic_entity") =>
          Seq(new BioEventMention(
          Seq("Binding", "SimpleEvent", "Event", "PossibleController"),
          m.trigger,
          m.arguments - "theme1" - "theme2" + ("theme" -> gen1),
          m.sentence,
          m.document,
          m.keep,
          m.foundBy
          ))
        case (Nil, gen2) if gen2.exists(t => t matches "Generic_entity") =>
          Seq(new BioEventMention(
            Seq("Binding", "SimpleEvent", "Event", "PossibleController"),
            m.trigger,
            m.arguments - "theme1" - "theme2" + ("theme" -> gen2),
            m.sentence,
            m.document,
            m.keep,
            m.foundBy
          ))
        case (t1s, t2s) =>
          val pairs = for {
            t1 <- t1s
            t2 <- t2s
          } yield List(t1, t2)
          mkBindingsFromPairs(pairs, m)
        // bindings with 0 or 1 themes should be deleted
        case _ => Nil
      }
  }

  def mkBindingsFromPairs(pairs: Seq[Seq[BioMention]], original: EventMention): Seq[Mention] = for {
    Seq(theme1, theme2) <- pairs
    if !sameEntityID(theme1, theme2)
  } yield {
    if (theme1.text.toLowerCase == "ubiquitin") {
      val arguments = Map("theme" -> Seq(theme2))
      new BioEventMention(
        Seq("Ubiquitination", "SimpleEvent", "Event", "PossibleController"),
        original.trigger,
        arguments,
        original.sentence,
        original.document,
        original.keep,
        original.foundBy)
    } else if (theme2.text.toLowerCase == "ubiquitin") {
      val arguments = Map("theme" -> Seq(theme1))
      new BioEventMention(
        Seq("Ubiquitination", "SimpleEvent", "Event", "PossibleController"),
        original.trigger,
        arguments,
        original.sentence,
        original.document,
        original.keep,
        original.foundBy)
    } else {
      val arguments = Map("theme" -> Seq(theme1, theme2))
      new BioEventMention(
        original.labels,
        original.trigger,
        arguments,
        original.sentence,
        original.document,
        original.keep,
        original.foundBy)
    }
  }

  /**
   * Promote any Sites in the Modifications of a SimpleEvent argument to an event argument "site"
   */
  def siteSniffer(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: BioEventMention if m matches "SimpleEvent" =>
      val additionalSites: Seq[Mention] = m.arguments.values.flatten.toSeq.flatMap { case m: BioMention =>
        // get EventSite Modifications
        val eventSites: Seq[EventSite] = m.modifications.toSeq flatMap {
          case es:EventSite => Some(es)
          case _ => None
        }
        // Remove EventSite modifications
        eventSites.foreach(m.modifications -= _)
        // get sites from EventSites
        eventSites.map(_.site)
      }

      // Gather up our sites
      val allSites = additionalSites ++ m.arguments.getOrElse("site", Nil)

      // Do we have any sites?
      if (allSites.isEmpty) Seq(m)
      else {
        val allButSite = m.arguments - "site"
        // Create a separate EventMention for each Site
        // FIXME this splitting seems arbitrary
        // why do each SimpleEvent has a single site?
        // if it is the theme's site, then why are we extracting sites for all args?
        for (site <- allSites.distinct) yield {
          val updatedArgs = allButSite + ("site" -> Seq(site))
          new BioEventMention(
            m.labels,
            m.trigger,
            updatedArgs,
            m.sentence,
            m.document,
            m.keep,
            m.foundBy)
        }
      }

    // If it isn't a SimpleEvent, assume there is nothing more to do
    case m => Seq(m)
  }

  def keepIfValidArgs(mentions: Seq[Mention], state: State): Seq[Mention] =
    mentions.filter(validArguments(_, state))

  def splitSimpleEvents(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if m matches "SimpleEvent" =>
      // Do we have a regulation?
      if (m.arguments.keySet contains "cause") {
        // FIXME There could be more than one cause...
        val cause: Seq[Mention] = m.arguments("cause")
        val evArgs = m.arguments - "cause"
        val ev = new BioEventMention(
          m.labels, m.trigger, evArgs, m.sentence, m.document, m.keep, m.foundBy, true)
        // make sure the regulation is valid
        val controlledArgs: Set[Mention] = evArgs.values.flatten.toSet
        // controller of an event should not be an arg in the controlled
        if (cause.forall(c => !controlledArgs.contains(c))) {
          val regArgs = Map("controlled" -> Seq(ev), "controller" -> cause)
          val reg = new BioRelationMention(
            Seq("Positive_regulation", "Regulation", "ComplexEvent", "Event", "PossibleController"),
            regArgs, m.sentence, m.document, m.keep, m.foundBy)
          // negations should be propagated to the newly created Positive_regulation
          val (negMods, otherMods) = m.toBioMention.modifications.partition(_.isInstanceOf[Negation])
          reg.modifications = negMods
          ev.modifications = otherMods
          Seq(reg, ev)
        } else  Nil
      } else Seq(m.toBioMention)
    case m => Seq(m.toBioMention)
  }

  // Reach currently doesn't support recursive events whose participants are also recursive events.
  // This method filters them out so that we don't encounter them during serialization.
  def filterEventsWithExtraRecursion(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    m <- mentions
    if !m.arguments.values.flatten.toSeq.exists((arg:Mention) => arg matches "Regulation")
  } yield m

  /** global action for EventEngine */
  def cleanupEvents(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val r0 = filterEventsWithExtraRecursion(mentions, state)
    val r1 = siteSniffer(r0, state)
    val r2 = keepIfValidArgs(r1, state)
    val r3 = NegationHandler.detectNegations(r2, state)
    val r4 = HypothesisHandler.detectHypotheses(r3, state)
    val r5 = splitSimpleEvents(r4, state)
    r5
  }


  // HELPER FUNCTIONS


  /** retrieves the appropriate modification label */
  def getModificationLabel(text: String): String = text.toLowerCase match {
    case string if deAcetylatPat.findPrefixOf(string).isDefined => "Deacetylation"
    case string if deFarnesylatPat.findPrefixOf(string).isDefined => "Defarnesylation"
    case string if deGlycosylatPat.findPrefixOf(string).isDefined => "Deglycosylation"
    case string if deHydrolyPat.findPrefixOf(string).isDefined => "Dehydrolysis"
    case string if deHydroxylatPat.findPrefixOf(string).isDefined => "Dehydroxylation"
    case string if deMethylatPat.findPrefixOf(string).isDefined => "Demethylation"
    case string if dePhosphorylatPat.findPrefixOf(string).isDefined => "Dephosphorylation"
    case string if deRibosylatPat.findPrefixOf(string).isDefined => "Deribosylation"
    case string if deSumoylatPat.findPrefixOf(string).isDefined => "Desumoylation"
    case string if deUbiquitinatPat.findPrefixOf(string).isDefined => "Deubiquitination"
    case string if string contains "acetylat" => "Acetylation"
    case string if string contains "farnesylat" => "Farnesylation"
    case string if string contains "glycosylat" => "Glycosylation"
    case string if string contains "hydroly" => "Hydrolysis"
    case string if string contains "hydroxylat" => "Hydroxylation"
    case string if string contains "methylat" => "Methylation"
    case string if string contains "phosphorylat" => "Phosphorylation"
    case string if string contains "ribosylat" => "Ribosylation"
    case string if string contains "sumoylat" => "Sumoylation"
    case string if string contains "ubiquitinat" => "Ubiquitination"
    case _ => "UNKNOWN"
  }

  /** Gets a sequence of mentions and returns only the ones that have
    * SimpleEvent controllers. If none is found, returns all mentions.
    */
  def preferSimpleEventControllers(mentions: Seq[Mention]): Seq[Mention] = {
    // get events that have a SimpleEvent as a controller
    // assuming that events can only have one controller
    val eventsWithSimpleController = mentions.filter { m =>
      m.arguments.contains("controller") && m.arguments("controller").head.matches("SimpleEvent")
    }
    if (eventsWithSimpleController.nonEmpty) eventsWithSimpleController else mentions
  }

  /** Gets a mention. If it is an EventMention with a polarized label
    * and it is negated an odd number of times, returns a new mention
    * with the label flipped. Or else it returns the mention unmodified */
  def switchLabel(mention: BioMention): BioMention = mention match {
    case m: BioEventMention =>
      val trigger = m.trigger
      val arguments = m.arguments.values.flatten
      // get token indices to exclude in the negation search
      val excluded = trigger.tokenInterval.toSet ++ arguments.flatMap(_.tokenInterval)
      // count total number of negatives between trigger and each argument
      val numNegatives = arguments.map(arg => countSemanticNegatives(trigger, arg, excluded)).sum
      if (numNegatives % 2 != 0) { // odd number of negatives
        val newLabels = flipLabel(m.labels.head) +: m.labels.tail
        // trigger labels should match event labels
        val newTrigger = m.trigger.copy(labels = newLabels)
        // return new mention with flipped label
        new BioEventMention(newLabels, newTrigger, m.arguments, m.sentence, m.document, m.keep, m.foundBy)
      } else {
        m // return mention unmodified
      }
    case m => m
  }

  /** Gets a trigger, an argument and a set of tokens to be ignored.
    * Returns the number of semantic negatives found in the shortest possible path
    * between the trigger and the argument.
    */
  def countSemanticNegatives(trigger: Mention, arg: Mention, excluded: Set[Int]): Int = {
    // it is possible for the trigger and the arg to be in different sentences because of coreference
    if (trigger.sentence != arg.sentence) return 0
    val deps = trigger.sentenceObj.dependencies.get
    // find the shortest path between any token in the trigger and any token in the argument
    var shortestPath: Seq[Int] = null
    for (tok1 <- trigger.tokenInterval; tok2 <- arg.tokenInterval) {
      val path = deps.shortestPath(tok1, tok2, ignoreDirection = true)
      if (shortestPath == null || path.length < shortestPath.length) {
        shortestPath = path
      }
    }
    val shortestPathWithMods = addAdjectivalModifiers(shortestPath, deps)
    // get all tokens considered negatives
    val negatives = for {
      tok <- shortestPathWithMods
      if !excluded.contains(tok)
      lemma = trigger.sentenceObj.lemmas.get(tok)
      if SEMANTIC_NEGATIVE_PATTERN.findFirstIn(lemma).isDefined
    } yield tok
    // return number of negatives
    negatives.size
  }

  /**
   * Adds adjectival modifiers to all elements in the given path
   * This is necessary so we can properly inspect the semantic negatives,
   *   which are often not in the path, but modify tokens in it,
   *   "*decreased* PTPN13 expression increases phosphorylation of EphrinB1"
   */
  def addAdjectivalModifiers(tokens: Seq[Int], deps: DirectedGraph[String]): Seq[Int] = for {
    t <- tokens
    token <- t +: getModifiers(t, deps)
  } yield token

  def getModifiers(token: Int, deps: DirectedGraph[String]): Seq[Int] = for {
    (tok, dep) <- deps.getOutgoingEdges(token)
    if MODIFIER_LABELS.findFirstIn(dep).isDefined
  } yield tok

  /** gets a polarized label and returns it flipped */
  def flipLabel(label: String): String =
    if (label startsWith "Positive_")
      "Negative_" + label.substring(9)
    else if (label startsWith "Negative_")
      "Positive_" + label.substring(9)
    else sys.error("ERROR: Must have a polarized label here!")


  /** Test whether the given mention has a controller argument. */
  def hasController(mention: Mention): Boolean = mention.arguments.get("controlled").isDefined

  /** Gets a mention and checks that the controller and controlled are different.
    * Returns true if either the controller or the controlled is missing,
    * or if they are both present and are distinct.
    */
  def hasDistinctControllerControlled(m: Mention): Boolean = {
    val controlled = m.arguments.getOrElse("controlled", Nil)
    val controller = m.arguments.getOrElse("controller", Nil)
    // if no controller or no controlled then we are good
    if (controlled.isEmpty || controller.isEmpty) true
    else {
      // we are only concerned with the first controlled and controller
      val c1 = controlled.head.toBioMention
      val c2 = controller.head.toBioMention
      if (c1 == c2) false // they are the same mention
      else (c1.grounding, c2.grounding) match {
        // if they are grounded the grounding should be different
        case (Some(g1), Some(g2)) => g1 != g2
        case _ => true // seems like they are different
      }
    }
  }

  /** checks if a mention has a controller/controlled
    * arguments with syntactic paths from the trigger
    * that overlap
    */
  def hasSynPathOverlap(m: Mention): Boolean = {
    val controlled = m.arguments.getOrElse("controlled", Nil)
    val controller = m.arguments.getOrElse("controller", Nil)
    if (m.paths.isEmpty) false
    else if (controlled.isEmpty || controller.isEmpty) false
    else {
      // we are only concerned with the first controlled and controller
      val p1 = m.getPath("controlled", controlled.head)
      val p2 = m.getPath("controller", controller.head)
      if (p1.nonEmpty && p2.nonEmpty) {
        p1.head == p2.head
      } else false
    }
  }

  /** Gets a BioEventMention. If it is not a SimpleEvent it returns None.
    * If it is a SimpleEvent it will return an entity that represents
    * the product of the event: a complex for a binding and an entity
    * with a PTM for any other kind of SimpleEvent.
    */
  def convertEventToEntity(event: BioEventMention): Option[BioMention] = {
    if (!event.matches("SimpleEvent") || event.matches("Generic_event")) {
      // we only handle simple events
      None
    } else if (event matches "Binding") {
      // create a relationMention that represents the Complex produced by this binding
      val complex = new BioRelationMention(
        // we need to specify all labels, taxonomy is not available here
        Seq("Complex", "MacroMolecule", "BioChemicalEntity", "Entity", "PossibleController"),
        event.arguments,
        event.sentence,
        event.document,
        event.keep,
        event.foundBy)
      Some(complex)
    } else {
      // get the theme of the event (assume only one theme)
      val entity = event.arguments("theme").head.toBioMention
      // get an optional site (assume only one site)
      val siteOption = event.arguments.get("site").map(_.head)
      // create new mention for the entity
      val modifiedEntity = new BioTextBoundMention(
        entity.labels,
        entity.tokenInterval,
        entity.sentence,
        entity.document,
        entity.keep,
        entity.foundBy)
      // attach a modification based on the event trigger
      val label = getModificationLabel(event.trigger.text)
      BioMention.copyAttachments(entity, modifiedEntity)
      modifiedEntity.modifications += PTM(label, evidence = Some(event.trigger), site = siteOption)
      Some(modifiedEntity)
    }
  }

  /** sorts a sequence of Mentions so that mentions with event controllers appear first */
  def sortMentionsByController(mentions: Seq[Mention]): Seq[Mention] = mentions sortWith { (m1, m2) =>
    // get the controller of the first mention
    val ctrl1 = m1.arguments.get("controller")
    val ctrl2 = m2.arguments.get("controller")
    (ctrl1, ctrl2) match {
      case (Some(Seq(c1)), Some(Seq(c2))) if c1.matches("Event") && !c2.matches("Event") => true
      case (Some(Seq(c1)), None) => true
      case _ => false
    }
  }

  /** Returns true if both mentions are grounded to the same entity */
  def sameEntityID(m1: BioMention, m2: BioMention): Boolean = {
    require(m1.isGrounded, "mention must be grounded")
    require(m2.isGrounded, "mention must be grounded")
    m1.grounding == m2.grounding
  }

  def removeDummy(m: BioMention): BioMention = m match {
    case em: BioEventMention => // we only need to do this for events
      if (em.arguments contains "dummy") {
        new BioEventMention(
          em.labels,
          em.trigger,
          em.arguments - "dummy",
          em.sentence,
          em.document,
          em.keep,
          em.foundBy)
      } else em
    case _ => m
  }

  def validArguments(mention: Mention, state: State): Boolean = mention match {
    // TextBoundMentions don't have arguments
    case _: BioTextBoundMention => true
    // RelationMentions don't have triggers, so we can't inspect the path
    case _: BioRelationMention => true
    // EventMentions are the only ones we can really check
    case m: BioEventMention =>
      // get simple chemicals in arguments
      val args = m.arguments.values.flatten
      val simpleChemicals = args.filter(_ matches "Simple_chemical")
      // if there are no simple chemicals then we are done
      if (simpleChemicals.isEmpty) true
      else {
        for (chem <- simpleChemicals) {
          if (proteinBetween(m.trigger, chem, state)) {
            return false
          }
        }
        true
      }
  }

  def proteinBetween(trigger: Mention, arg: Mention, state: State): Boolean = {
    // it is possible for the trigger and the arg to be in different sentences
    // because of coreference
    if (trigger.sentence != arg.sentence) false
    else trigger.sentenceObj.dependencies match {
      // if for some reason we don't have dependencies
      // then there is nothing we can do
      case None => false
      case Some(deps) => for {
        tok1 <- trigger.tokenInterval
        tok2 <- arg.tokenInterval
        path = deps.shortestPath(tok1, tok2, ignoreDirection = true)
        node <- path
        if state.mentionsFor(trigger.sentence, node, "Gene_or_gene_product").nonEmpty
        if !consecutivePreps(path, deps)
      } return true
        // if we reach this point then we are good
        false
    }
  }

  // hacky solution to the prepositional attachment problem
  // that affects the proteinBetween method
  def consecutivePreps(path: Seq[Int], deps: DirectedGraph[String]): Boolean = {
    val pairs = for (i <- path.indices.tail) yield (path(i-1), path(i))
    val edges = for ((n1, n2) <- pairs) yield {
      deps.getEdges(n1, n2, ignoreDirection = true).map(_._3)
    }
    for {
      i <- edges.indices.tail
      if edges(i-1).exists(_.startsWith("prep"))
      if edges(i).exists(_.startsWith("prep"))
    } return true
    false
  }

}

object DarpaActions {

  // These are used to detect semantic inversions of regulations/activations. See DarpaActions.countSemanticNegatives
  val SEMANTIC_NEGATIVE_PATTERN = "attenu|block|deactiv|decreas|degrad|diminish|disrupt|impair|imped|inhibit|knockdown|limit|lower|negat|reduc|reliev|repress|restrict|revers|slow|starv|suppress|supress".r

  val MODIFIER_LABELS = "amod".r

  // patterns for "reverse" modifications
  val deAcetylatPat     = "(?i)de-?acetylat".r
  val deFarnesylatPat   = "(?i)de-?farnesylat".r
  val deGlycosylatPat   = "(?i)de-?glycosylat".r
  val deHydrolyPat      = "(?i)de-?hydroly".r
  val deHydroxylatPat   = "(?i)de-?hydroxylat".r
  val deMethylatPat     = "(?i)de-?methylat".r
  val dePhosphorylatPat = "(?i)de-?phosphorylat".r
  val deRibosylatPat    = "(?i)de-?ribosylat".r
  val deSumoylatPat     = "(?i)de-?sumoylat".r
  val deUbiquitinatPat  = "(?i)de-?ubiquitinat".r

}
