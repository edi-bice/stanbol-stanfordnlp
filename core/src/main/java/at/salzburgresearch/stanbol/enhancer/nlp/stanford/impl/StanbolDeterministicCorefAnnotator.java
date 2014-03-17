package at.salzburgresearch.stanbol.enhancer.nlp.stanford.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import edu.stanford.nlp.dcoref.Constants;
import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.dcoref.CorefChain.CorefMention;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations;
import edu.stanford.nlp.dcoref.Document;
import edu.stanford.nlp.dcoref.Mention;
import edu.stanford.nlp.dcoref.MentionExtractor;
import edu.stanford.nlp.dcoref.RuleBasedCorefMentionFinder;
import edu.stanford.nlp.dcoref.SieveCoreferenceSystem;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.Annotator;
import edu.stanford.nlp.pipeline.DeterministicCorefAnnotator;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeCoreAnnotations;
import edu.stanford.nlp.util.ArraySet;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.IntTuple;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.PropertiesUtils;

/**
 * It is an almost exact redefinition of the {@link DeterministicCorefAnnotator} with the single
 * addition of the parse {@link Annotator} variable which is needed at some point in the
 * coref processing.
 * Normally in the Stanford NLP the parse {@link Annotator} is fetched via the 
 * StanfordCoreNLP.getExistingAnnotator("parse") method, which uses the static pool
 * register. In our case however the static pool does not exist and is replaced by the pool
 * at {@link LangPipeline} level so we need to use that one to get the existing parse
 * {@link Annotator} for the given language and pass it to this class.
 *
 * NOTE: since most of the code is the same as the one in {@link DeterministicCorefAnnotator}
 * when an upgrade to a newer version of the Stanford NLP lib is done this class needs to 
 * be updated as well. Code introduced for Stanbol is marked with <stanbolAddition></stanbolAddition>
 * 
 * @author Cristian Petroaca
 */

public class StanbolDeterministicCorefAnnotator implements Annotator {

  private static final boolean VERBOSE = false;

  private final MentionExtractor mentionExtractor;
  private final SieveCoreferenceSystem corefSystem;


  // for backward compatibility
  private final boolean OLD_FORMAT;

  private final boolean allowReparsing;
  
  //<stanbolAddition>
  private Annotator parserProcessor;
  //</stanbolAddition>
  
  public StanbolDeterministicCorefAnnotator(Properties props) {
    try {
      corefSystem = new SieveCoreferenceSystem(props);
      mentionExtractor = new MentionExtractor(corefSystem.dictionaries(), corefSystem.semantics());
      OLD_FORMAT = Boolean.parseBoolean(props.getProperty("oldCorefFormat", "false"));
      allowReparsing = PropertiesUtils.getBool(props, Constants.ALLOW_REPARSING_PROP, Constants.ALLOW_REPARSING);
    } catch (Exception e) {
      System.err.println("ERROR: cannot create DeterministicCorefAnnotator!");
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  public static String signature(Properties props) {
    return SieveCoreferenceSystem.signature(props);
  }

  @Override
  public void annotate(Annotation annotation){
    try {
      List<Tree> trees = new ArrayList<Tree>();
      List<List<CoreLabel>> sentences = new ArrayList<List<CoreLabel>>();

      // extract trees and sentence words
      // we are only supporting the new annotation standard for this Annotator!
      boolean hasSpeakerAnnotations = false;
      if (annotation.containsKey(CoreAnnotations.SentencesAnnotation.class)) {
        // int sentNum = 0;
        for (CoreMap sentence: annotation.get(CoreAnnotations.SentencesAnnotation.class)) {
          List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
          sentences.add(tokens);
          Tree tree = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
          trees.add(tree);

          if (!hasSpeakerAnnotations) {
            // check for speaker annotations
            for (CoreLabel t:tokens) {
              if (t.get(CoreAnnotations.SpeakerAnnotation.class) != null) {
                hasSpeakerAnnotations = true;
                break;
              }
            }
          }
          MentionExtractor.mergeLabels(tree, tokens);
          MentionExtractor.initializeUtterance(tokens);
        }
      } else {
        System.err.println("ERROR: this coreference resolution system requires SentencesAnnotation!");
        return;
      }
      if (hasSpeakerAnnotations) {
        annotation.set(CoreAnnotations.UseMarkedDiscourseAnnotation.class, true);
      }

      // extract all possible mentions
      // this is created for each new annotation because it is not threadsafe
      //<stanbolAddition>
      if (parserProcessor == null) {
    	  throw new RuntimeException("parse annotator cannot be null");
      }
      RuleBasedCorefMentionFinder finder = new StanbolRuleBasedCorefMentionFinder(parserProcessor, allowReparsing);
      //</stanbolAddition>
      
      List<List<Mention>> allUnprocessedMentions = finder.extractPredictedMentions(annotation, 0, corefSystem.dictionaries());

      // add the relevant info to mentions and order them for coref
      Document document = mentionExtractor.arrange(annotation, sentences, trees, allUnprocessedMentions);
      List<List<Mention>> orderedMentions = document.getOrderedMentions();
      if(VERBOSE){
        for(int i = 0; i < orderedMentions.size(); i ++){
          System.err.printf("Mentions in sentence #%d:\n", i);
          for(int j = 0; j < orderedMentions.get(i).size(); j ++){
            System.err.println("\tMention #" + j + ": " + orderedMentions.get(i).get(j).spanToString());
          }
        }
      }

      Map<Integer, CorefChain> result = corefSystem.coref(document);
      annotation.set(CorefCoreAnnotations.CorefChainAnnotation.class, result);

      // for backward compatibility
      if(OLD_FORMAT) {
        List<Pair<IntTuple, IntTuple>> links = SieveCoreferenceSystem.getLinks(result);

        if(VERBOSE){
          System.err.printf("Found %d coreference links:\n", links.size());
          for(Pair<IntTuple, IntTuple> link: links){
            System.err.printf("LINK (%d, %d) -> (%d, %d)\n", link.first.get(0), link.first.get(1), link.second.get(0), link.second.get(1));
          }
        }

        //
        // save the coref output as CorefGraphAnnotation
        //

        // cdm 2013: this block didn't seem to be doing anything needed....
        // List<List<CoreLabel>> sents = new ArrayList<List<CoreLabel>>();
        // for (CoreMap sentence: annotation.get(CoreAnnotations.SentencesAnnotation.class)) {
        //   List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
        //   sents.add(tokens);
        // }

        // this graph is stored in CorefGraphAnnotation -- the raw links found by the coref system
        List<Pair<IntTuple, IntTuple>> graph = new ArrayList<Pair<IntTuple,IntTuple>>();

        for(Pair<IntTuple, IntTuple> link: links){
          //
          // Note: all offsets in the graph start at 1 (not at 0!)
          //       we do this for consistency reasons, as indices for syntactic dependencies start at 1
          //
          int srcSent = link.first.get(0);
          int srcTok = orderedMentions.get(srcSent - 1).get(link.first.get(1)-1).headIndex + 1;
          int dstSent = link.second.get(0);
          int dstTok = orderedMentions.get(dstSent - 1).get(link.second.get(1)-1).headIndex + 1;
          IntTuple dst = new IntTuple(2);
          dst.set(0, dstSent);
          dst.set(1, dstTok);
          IntTuple src = new IntTuple(2);
          src.set(0, srcSent);
          src.set(1, srcTok);
          graph.add(new Pair<IntTuple, IntTuple>(src, dst));
        }
        annotation.set(CorefCoreAnnotations.CorefGraphAnnotation.class, graph);

        for (CorefChain corefChain : result.values()) {
          if(corefChain.getMentionsInTextualOrder().size() < 2) continue;
          Set<CoreLabel> coreferentTokens = Generics.newHashSet();
          for (CorefMention mention : corefChain.getMentionsInTextualOrder()) {
            CoreMap sentence = annotation.get(CoreAnnotations.SentencesAnnotation.class).get(mention.sentNum - 1);
            CoreLabel token = sentence.get(CoreAnnotations.TokensAnnotation.class).get(mention.headIndex - 1);
            coreferentTokens.add(token);
          }
          for (CoreLabel token : coreferentTokens) {
            token.set(CorefCoreAnnotations.CorefClusterAnnotation.class, coreferentTokens);
          }
        }
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }


  @Override
  public Set<Requirement> requires() {
    return new ArraySet<Requirement>(TOKENIZE_REQUIREMENT, SSPLIT_REQUIREMENT, POS_REQUIREMENT, NER_REQUIREMENT, PARSE_REQUIREMENT);
  }

  @Override
  public Set<Requirement> requirementsSatisfied() {
    return Collections.singleton(DETERMINISTIC_COREF_REQUIREMENT);
  }
  
  //<stanbolAddition>
  public void setParserAnnotator(Annotator parserAnnotator) {
	  parserProcessor = parserAnnotator;
  }
  //</stanbolAddition>
}