package com.coreference;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.*;

import edu.stanford.nlp.coref.CorefCoreAnnotations;
import edu.stanford.nlp.coref.data.CorefChain;
import edu.stanford.nlp.coref.data.CorefChain.CorefMention;
import edu.stanford.nlp.coref.data.Dictionaries.Animacy;
import edu.stanford.nlp.coref.data.Dictionaries.MentionType;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.PTBTokenizer.PTBTokenizerFactory;
import edu.stanford.nlp.util.CoreMap;

public class CoreferenceChainsOnPersons {
	String inputDir;
	boolean allMentions;
	Properties props;
	StanfordCoreNLP pipeline;
	PTBTokenizerFactory<Word> factory;
	PrintWriter writer;
	
	public CoreferenceChainsOnPersons(String inputDir,boolean allMentions) throws FileNotFoundException, UnsupportedEncodingException{
		this.inputDir = inputDir;		
		this.allMentions = allMentions;
		props = new Properties();
    	props.setProperty("annotators", "tokenize,ssplit,pos,lemma,ner,parse,mention,coref");
    	props.setProperty("parse.maxlen", "100");
    	props.setProperty("ssplit.eolonly", "true");
    	props.setProperty("tokenize.whitespace","true");
    	props.setProperty("coref.removeSingletonClusters","false");
    	pipeline = new StanfordCoreNLP(props);
    	factory = (PTBTokenizerFactory<Word>)PTBTokenizer.factory();
	}
	
	public void listFiles(String dataDirPath,ArrayList<File> allFiles){
		File[] files = new File(dataDirPath).listFiles();
		for (File file: files){
			if(file.isFile()){
				allFiles.add(file);
			}
			if(file.isDirectory()){
				listFiles(dataDirPath+"/"+file.getName(),allFiles);
			}
		}
	}
	
	public String getFileContent(File file) {
		String line, content = "";
		try {
			BufferedReader br = new BufferedReader(new FileReader(file));
			while ((line = br.readLine()) != null) {
				content += line + "\n";
			}
			br.close();
		} catch (IOException e) {
			e.printStackTrace();
		}	
		return content;
	}
	
	public Mention getHead(Mention mention,List<Mention> NEsInSentence,List<String> sentence) {
		if(NEsInSentence == null || NEsInSentence.size()==0)
			return null;
		List<Mention> personsInMention = new ArrayList<>();
		for(Mention person: NEsInSentence) {
			if(person.startTokenInd >= mention.startTokenInd && person.endTokenInd <= person.endTokenInd)
				personsInMention.add(person);
		}
		if(personsInMention.size()==0)
			return null;
		if(personsInMention.size()==1)
			return personsInMention.get(0);
		else {
			for(Mention person: personsInMention) {
				if(person.endTokenInd!=mention.endTokenInd
						&& (sentence.get(person.endTokenInd+1).contentEquals(",") 
								|| sentence.get(person.endTokenInd+1).contentEquals("who")))
					return person;
			}	
			for(Mention person: personsInMention) {
				if(person.endTokenInd == mention.endTokenInd)
					return person;
			}
			for(Mention person: personsInMention) {
				if(person.startTokenInd == person.endTokenInd)
					return person;
			}
			return personsInMention.get(0);
		}
	}
	
	public void getAllNamedPersonsAndSentences(Annotation document, Map<Integer,List<Mention>> persons,Map<Integer,List<String>> sentences) {
		
		int sentNum = 0;
		for (CoreMap sentence: document.get(CoreAnnotations.SentencesAnnotation.class)) {
			sentences.put(sentNum,new ArrayList<>());
			int tokenStartInd = 0, tokenEndInd = 0;
			String mention = "";
			for (CoreLabel token: sentence.get(CoreAnnotations.TokensAnnotation.class)) {
				String tokenText = token.get(CoreAnnotations.TextAnnotation.class);
				sentences.get(sentNum).add(tokenText.toString());
				if(!token.ner().contentEquals("PERSON")) {
					if(!mention.isEmpty()) {
						if(!persons.containsKey(sentNum))
							persons.put(sentNum, new ArrayList<>());
						//System.out.println(mention+" "+sentNum+" "+String.valueOf(tokenStartInd-1)+" "+String.valueOf(tokenEndInd-1));
						persons.get(sentNum).add(new Mention(mention,sentNum,tokenStartInd-1,tokenEndInd-1));
						mention = "";
					}
				} else if(mention.isEmpty()) {
					mention = tokenText;
					tokenStartInd = token.index();
					tokenEndInd = token.index();
				} else {
					mention = mention + " " + tokenText;
					tokenEndInd = token.index();
				}
			}
			sentNum++;
		}
		//System.out.println();
	}
	
	public void generateAndSelectChains(File file,Map<String,List<Mention>> chains) {
		Annotation document = new Annotation(getFileContent(file));
		try {
			pipeline.annotate(document);
		} catch (Exception e) {
			return ;
		}
		//map of sent num to named entity
		Map<Integer,List<Mention>> persons = new HashMap<>();
		Map<Integer,List<String>> sentences = new HashMap<>();
		getAllNamedPersonsAndSentences(document,persons,sentences);
		
		Set<String> pronouns = new HashSet<>();
		pronouns.add("they");
		pronouns.add("them");
		pronouns.add("their");
		pronouns.add("those");
		pronouns.add("these");
		pronouns.add("we");
		pronouns.add("our");
		pronouns.add("us");
		
		boolean found = false;	  
    	for (CorefChain cc : document.get(CorefCoreAnnotations.CorefChainAnnotation.class).values()) {
    		boolean selectChain = true;
    		if(cc.getMentionsInTextualOrder().size()==1 
    				&& (cc.getMentionsInTextualOrder().get(0).animacy.compareTo(Animacy.ANIMATE) != 0
    				       || cc.getMentionsInTextualOrder().get(0).mentionType.compareTo(MentionType.PROPER) != 0)) {
    			continue;
    		}
    		for(CorefMention mention : cc.getMentionsInTextualOrder()){
    			if(mention.animacy.compareTo(Animacy.ANIMATE) != 0
    					|| mention.mentionType.compareTo(MentionType.LIST) == 0
    					|| pronouns.contains(mention.mentionSpan.toLowerCase())) {
    				selectChain = false;
    				System.err.println("Rejecting!! " + mention.mentionSpan + " " + mention.mentionType + " " + mention.animacy);
    				break;
    			}
    		}
    		if(selectChain) {
    			//System.out.println("---SELECTED ---");
    			int chainLen = cc.getMentionsInTextualOrder().size();
    			for(CorefMention corefMention : cc.getMentionsInTextualOrder()){
					Mention mention = new Mention(corefMention.mentionSpan,corefMention.sentNum-1,corefMention.startIndex-1,corefMention.endIndex-2);
    				if(corefMention.mentionType.compareTo(MentionType.PROPER) == 0) {
    					Mention head = getHead(mention, persons.get(mention.sentNum), sentences.get(mention.sentNum));
    					if(head!=null) {
    						System.out.println(file.getName()+"<,>"+cc.getChainID()+"<,>"+mention.toString()+"<,>"+head.toString()+"<,>"+corefMention.mentionType);
    					//System.out.println(file.getName()+"<,>"+cc.getChainID()+"<,>"+mention.toString());
    					} else if(head==null && allMentions && chainLen!=1) {
        					System.out.println(file.getName()+"<,>"+cc.getChainID()+"<,>"+mention.toString()+"<,>"+mention.toString()+"<,>NOMINAL");
    					}
						found = true;
    				} else {
    					if(allMentions) {
        					System.out.println(file.getName()+"<,>"+cc.getChainID()+"<,>"+mention.toString()+"<,>"+mention.toString()+"<,>"+corefMention.mentionType);
        					found = true;
    					}
    				}
    			}
    		}
    	}
    	if(found)
    		System.out.println();
	}
	
	public static void main(String[] args) throws FileNotFoundException, UnsupportedEncodingException{
		CoreferenceChainsOnPersons data = new CoreferenceChainsOnPersons(args[0],args[1].contentEquals("all"));
		//CoreferenceChainsOnPersons data = new CoreferenceChainsOnPersons("data/files",true);
		ArrayList<File> allFiles = new ArrayList<File>(); 
		data.listFiles(data.inputDir, allFiles);
		Map<String,List<Mention>> chains = new HashMap<>();
		for(File file: allFiles) {
			chains.clear();
			data.generateAndSelectChains(file,chains);
		}
	}
}
