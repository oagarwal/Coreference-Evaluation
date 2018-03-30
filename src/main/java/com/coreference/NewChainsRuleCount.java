package com.training;

import java.io.BufferedReader;

public class NewChainsRuleCount {
	String inputDir;
	Properties props;
	StanfordCoreNLP pipeline;
	PTBTokenizerFactory<Word> factory;
	PrintWriter writer;
	Map<String,List<String>> nickToFullName;
	
	public NewChainsRuleCount(String inputDir) throws FileNotFoundException, UnsupportedEncodingException{
		this.inputDir = inputDir;		
		props = new Properties();
    	props.setProperty("annotators", "tokenize,ssplit,pos,lemma,ner,parse,mention,coref");
    	props.setProperty("parse.maxlen", "100");
    	//props.setProperty("ssplit.eolonly", "true");
    	//props.setProperty("tokenize.whitespace","true");
    	props.setProperty("coref.removeSingletonClusters","false");
    	pipeline = new StanfordCoreNLP(props);
    	factory = (PTBTokenizerFactory<Word>)PTBTokenizer.factory();
    	
    	nickToFullName = new HashMap<>();
    	try {
    		BufferedReader brn = new BufferedReader(new FileReader("nicknames.txt"));
        	String line;
			while ((line = brn.readLine()) != null) {
				String[] temp = line.split("\\s");
				temp[0] = temp[0].toLowerCase();
				temp[1] = temp[1].toLowerCase();
				if(!nickToFullName.containsKey(temp[0]))
					nickToFullName.put(temp[0], new ArrayList<>());
				nickToFullName.get(temp[0]).add(temp[1]);
			}
	    	brn.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
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
			if(person.startTokenInd >= mention.startTokenInd && person.endTokenInd <= mention.endTokenInd)
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
	
	public String getFirstName(String name) {
		String punctutations = "!\"#$%&\\\'()*+,-./:;<=>?@[\\]^_`{|}~";
		name = name.trim().toLowerCase().replace(" - ", "-");
		String[] vals = name.split(" ");
		for(String val: vals) {
			if(!punctutations.contains(val))
				return val;
		}
		return "";
	}
	
	public String getLastName(String name) {
		String punctutations = "!\"#$%&\\\'()*+,-./:;<=>?@[\\]^_`{|}~";
		Set<String> suffixes = new HashSet<>(Arrays.asList("ii","iii","iv","jr","jr.","sr","sr.","\'s"));
		name = name.trim().toLowerCase().replace(" - ", "-");
		List<String> vals = Arrays.asList(name.split(" "));
		Collections.reverse(vals);
		for(String val: vals) {
			if(!punctutations.contains(val) && !suffixes.contains(val))
				return val;
		}
		return "";
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
	}
	
	public void generateAndSelectChains(File file,Map<String,List<Mention>> chains) {
		
		System.out.println(file.getName());
		System.err.println(file.getName());
		
		int multipleEntitiesInSameChain = 0;
		int singletonAddedToRightChain = 0;
		int origClusterCount = 0;
		int newClusterCount = 0;
		
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
		
		Map<String,List<DetailedMention>> newChains = new HashMap<>();
		Map<String,List<DetailedMention>> singletonChains = new HashMap<>();
		Map<String,Set<String>> firstToLast = new HashMap<>();
		Map<String,Set<String>> lastToFirst = new HashMap<>();
		HashSet<String> allFirstNames = new HashSet<>();
		HashSet<String> allLastNames = new HashSet<>();
		
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
    				break;
    			}
    		}
;
    		if(selectChain) {
    			//STEP 1 - Collect mentions, first names and last names
    			List<DetailedMention> tempDm = new ArrayList<>();
    			Set<String> tempFirstNames = new HashSet<>();
    			Set<String> tempLastNames = new HashSet<>();
    			int chainLen = cc.getMentionsInTextualOrder().size();
    			if(chainLen>1)
    				origClusterCount++;
    			for(CorefMention corefMention : cc.getMentionsInTextualOrder()){
					Mention mention = new Mention(corefMention.mentionSpan,corefMention.sentNum-1,corefMention.startIndex-1,corefMention.endIndex-2);
					if(corefMention.mentionType.compareTo(MentionType.PROPER) == 0) {
    					Mention head = getHead(mention, persons.get(mention.sentNum), sentences.get(mention.sentNum));
    					if(head!=null) {
    						String firstName = getFirstName(head.mention());
    						String lastName = getLastName(head.mention());
    						tempLastNames.add(lastName);
    						allLastNames.add(lastName);
    						if(!firstName.contentEquals(lastName)) {
    							tempFirstNames.add(firstName);
    							allFirstNames.add(firstName);
    							if(!firstToLast.containsKey(firstName))
    								firstToLast.put(firstName, new HashSet<>());
    							if(!lastToFirst.containsKey(lastName))
    								lastToFirst.put(lastName, new HashSet<>());
    							firstToLast.get(firstName).add(lastName);
    							lastToFirst.get(lastName).add(firstName);
    							if(chainLen==1) {
    								if(!singletonChains.containsKey(firstName + " " + lastName)) 
    									singletonChains.put(firstName + " " + lastName, new ArrayList<>());
    								singletonChains.get(firstName + " " + lastName).add(new DetailedMention(mention, head, firstName + " " + lastName,"PROPER"));
    							} else 
    								tempDm.add(new DetailedMention(mention, head, firstName + " " + lastName,"PROPER"));
    						} else {
    							if(chainLen==1) {
    								if(!singletonChains.containsKey(firstName)) 
    									singletonChains.put(firstName, new ArrayList<>());
    								singletonChains.get(firstName).add(new DetailedMention(mention, head, firstName,"PROPER"));
    							} else
    								tempDm.add(new DetailedMention(mention, head, firstName,"PROPER"));
    						}
    					} else if(head==null && chainLen!=1) {
    						tempDm.add(new DetailedMention(mention, mention, String.valueOf(cc.getChainID()),"NOMINAL"));
    					}
    				} else {
						tempDm.add(new DetailedMention(mention, mention, String.valueOf(cc.getChainID()),corefMention.mentionType.toString()));
    				}
    			}
    			
    			List<String> tempFirstList = new ArrayList<>(tempFirstNames);
    			List<String> tempLastList = new ArrayList<>(tempLastNames);
    			
    			//STEP 2 - Create chains
    			
    			//Check for name variation 
    			if(tempFirstNames.size() > 1){
    				List<String> removefn = new ArrayList<>();
    				for(String fn: tempFirstNames) {
    					if(nickToFullName.containsKey(fn)) {
    						for(String candidate: nickToFullName.get(fn)) {
    							if(tempFirstNames.contains(candidate)) {
    								removefn.add(fn);
    								break;
    							}
    						}
    					}
    				}
    				for(String fn: removefn) {
    					tempFirstNames.remove(fn);
    				}
    				
    			}
    			
    			if(tempFirstNames.size() == 0 && tempLastNames.size() == 0) {
    				if(!newChains.containsKey(String.valueOf(cc.getChainID())))
    					newChains.put(String.valueOf(cc.getChainID()), tempDm);
    				else
    					newChains.get(String.valueOf(cc.getChainID())).addAll(tempDm);
    			} else if (tempFirstNames.size() == 1 && tempLastNames.size() == 1) {
    				if(!newChains.containsKey(tempFirstList.get(0) + " " + tempLastList.get(0)))
    					newChains.put(tempFirstList.get(0) + " " + tempLastList.get(0), tempDm);
    				else
    					newChains.get(tempFirstList.get(0) + " " + tempLastList.get(0)).addAll(tempDm);
    			} else if (tempFirstNames.size() == 0 && tempLastNames.size() == 1) {
    				if(!newChains.containsKey(tempLastList.get(0)))
    					newChains.put(tempLastList.get(0), tempDm);
    				else
    					newChains.get(tempLastList.get(0)).addAll(tempDm);
    			} else if (tempFirstNames.size() == 1 && tempLastNames.size() == 0) {
    				if(!newChains.containsKey(tempFirstList.get(0)))
    					newChains.put(tempFirstList.get(0), tempDm);
    				else
    					newChains.get(tempFirstList.get(0)).addAll(tempDm);
    			} else {
    				multipleEntitiesInSameChain++;
    				System.err.println("Multiple entities in same chains");
    				//break down chains
    				for(DetailedMention dm: tempDm) {
    					if(dm.type.contentEquals("PROPER")) {
    						System.err.println(dm.toString());
    						if(!newChains.containsKey(dm.chainId))
    							newChains.put(dm.chainId, new ArrayList<>());
    						newChains.get(dm.chainId).add(dm);
    					}
    				}
    				System.err.println();
    			}		
    		}
    	}
    	
    	//STEP 3 - Combine all chains. Separate out all singleton chains
    	Set<String> chainIds = new HashSet<>(newChains.keySet());
    	for(String chainId: chainIds) {
    		if(!chainId.contains(" ")) {
    			if((firstToLast.containsKey(chainId) && firstToLast.get(chainId).size() == 1) 
    					&& ((lastToFirst.containsKey(chainId) && lastToFirst.get(chainId).size() == 0) || !lastToFirst.containsKey(chainId))) {
    				String fullName = chainId + " " + firstToLast.get(chainId).toArray()[0];
    				//If it is a nick name and corresponding full name exists in doc, use that
    				if(nickToFullName.containsKey(chainId)) {
    					for(String candidate: nickToFullName.get(chainId)) {
    						if(allFirstNames.contains(candidate)) {
    							fullName = candidate + " " + firstToLast.get(chainId).toArray()[0];
    							break;
    						}
    					}
    				}
    				if(!newChains.containsKey(fullName))
    					newChains.put(fullName,new ArrayList<>());
    				newChains.get(fullName).addAll(newChains.get(chainId));
    				newChains.remove(chainId);
    			} else if (((firstToLast.containsKey(chainId) && firstToLast.get(chainId).size() == 0) || !firstToLast.containsKey(chainId))
    							&& (lastToFirst.containsKey(chainId) && lastToFirst.get(chainId).size() == 1)) {
    				String fullName = lastToFirst.get(chainId).toArray()[0] + " " + chainId;
    				if(!newChains.containsKey(fullName))
    					newChains.put(fullName,new ArrayList<>());
    				newChains.get(fullName).addAll(newChains.get(chainId));
    				newChains.remove(chainId);
    			} else if((firstToLast.containsKey(chainId) && firstToLast.get(chainId).size() == 0) || !firstToLast.containsKey(chainId)) {
    				//No corresponding last name, then check if it is a nick name and a last name exists for the full name
    				if(nickToFullName.containsKey(chainId)) {
    					for(String candidate: nickToFullName.get(chainId)) {
    						if((allFirstNames.contains(candidate)) 
    								&& (firstToLast.containsKey(candidate) && firstToLast.get(candidate).size() == 1)) {
    							//if last name exists for the full name, change chain id
    							String fullName = candidate + " " + firstToLast.get(candidate).toArray()[0];
    							if(!newChains.containsKey(fullName))
    		    					newChains.put(fullName,new ArrayList<>());
    		    				newChains.get(fullName).addAll(newChains.get(chainId));
    		    				newChains.remove(chainId);
    		    				break;
    						}
    					}
    				}
    			}
    		} else {
    			String[] vals = chainId.split(" ");
    			if(nickToFullName.containsKey(vals[0])) {
    				for(String candidate: nickToFullName.get(vals[0])) {
    					//replace by full name if it has no corresponding last name or same, provided first name exists
    					if((allFirstNames.contains(candidate))
    							&& (!firstToLast.containsKey(candidate)
    							|| (firstToLast.get(candidate).size() == 1) && (vals[1]).contentEquals(firstToLast.get(candidate).toArray()[0].toString()))) {
    						String fullName = candidate + " " + vals[1];
    						if(!newChains.containsKey(fullName))
		    					newChains.put(fullName,new ArrayList<>());
		    				newChains.get(fullName).addAll(newChains.get(chainId));
		    				newChains.remove(chainId);
		    				break;
    					}
    				}
    			}
    		}
    	}
    	
    	//STEP 4 - Add singleton clusters after checking for mention overlap
    	for(String chainId: singletonChains.keySet()) {
    		String fullName = "";
    		if(!chainId.contains(" ")) {
    			if((firstToLast.containsKey(chainId) && firstToLast.get(chainId).size() == 1) 
    					&& ((lastToFirst.containsKey(chainId) && lastToFirst.get(chainId).size() == 0) || !lastToFirst.containsKey(chainId))) {
    				fullName = chainId + " " + firstToLast.get(chainId).toArray()[0];
    				if(nickToFullName.containsKey(chainId)) {
    					for(String candidate: nickToFullName.get(chainId)) {
    						if(allFirstNames.contains(candidate)) {
    							fullName = candidate + " " + firstToLast.get(chainId).toArray()[0];
    							break;
    						}
    					}
    				}
    			} else if (((firstToLast.containsKey(chainId) && firstToLast.get(chainId).size() == 0) || !firstToLast.containsKey(chainId))
    							&& (lastToFirst.containsKey(chainId) && lastToFirst.get(chainId).size() == 1)) {
    				fullName = lastToFirst.get(chainId).toArray()[0] + " " + chainId;
    			} else {
    				fullName = chainId;
    				if(nickToFullName.containsKey(chainId)) {
    					for(String candidate: nickToFullName.get(chainId)) {
    						if((allFirstNames.contains(candidate)) 
    								&& (firstToLast.containsKey(candidate) && firstToLast.get(candidate).size() == 1)) {
    							//if last name exists for the full name, change chain id
    							fullName = candidate + " " + firstToLast.get(candidate).toArray()[0];
    							break;
    						}
    					}
    				}
    			}
    		} else {
    			fullName = chainId;
    			String[] vals = chainId.split(" ");
    			if(nickToFullName.containsKey(vals[0])) {
    				for(String candidate: nickToFullName.get(vals[0])) {
    					//replace by full name if it has no corresponding last name or same, provided first name exists
    					if((allFirstNames.contains(candidate))
    							&& (!firstToLast.containsKey(candidate)
    							|| (firstToLast.get(candidate).size() == 1) && (vals[1]).contentEquals(firstToLast.get(candidate).toArray()[0].toString()))) {
    						fullName = candidate + " " + vals[1];
		    				break;
    					}
    				}
    			}
    		}
    		
    		//Check if chain exists
    		if(newChains.containsKey(fullName)) {
    			for(DetailedMention smd: singletonChains.get(chainId)) {
    				boolean contained = false;
    				boolean contains = false;
    				List<DetailedMention> toRemove = new ArrayList<>();
    				for(DetailedMention nmd: newChains.get(fullName)) {
    					//if singleton mention in a mention already, don't add
    					if((smd.mention.sentNum == nmd.mention.sentNum)
    						&& (smd.mention.startTokenInd >= nmd.mention.startTokenInd)
    						&& (smd.mention.endTokenInd <= nmd.mention.endTokenInd)) {
    						contained = true;
    						break;
    					} 
    					if((smd.mention.sentNum == nmd.mention.sentNum)
    						&& (nmd.mention.startTokenInd >= smd.mention.startTokenInd)
    						&& (nmd.mention.endTokenInd <= smd.mention.endTokenInd)) {
    						toRemove.add(nmd);
    						contains = true;
    					}
    				}
    				for(DetailedMention nmd: toRemove)
    					newChains.get(fullName).remove(nmd);
    				if(!contained || contains) {
    					if(newChains.get(fullName).size() > 0) {
    						if(newChains.get(fullName).size() == 1)
    							singletonAddedToRightChain++;
    						singletonAddedToRightChain++;
    						System.err.println("Adding singleton " + smd.toString() + " to " + fullName + " of size " + String.valueOf(newChains.get(fullName).size()));
    					}
    					newChains.get(fullName).add(smd);
    				}
    			}
    		} else {
    			newChains.put(fullName, singletonChains.get(chainId));
    		}
    	}
    	
    	for(String chainId: newChains.keySet()) {
    		if(newChains.get(chainId).size()>1)
    			newClusterCount++;
    	}
    	
    	System.out.println("Singletons added " + String.valueOf(singletonAddedToRightChain));
    	System.out.println("Multiple entities " + String.valueOf(multipleEntitiesInSameChain));
    	System.out.println("Original cluster count " + String.valueOf(origClusterCount));
    	System.out.println("New cluster count " + String.valueOf(newClusterCount));
    	System.out.println("------------------\n");
    	System.err.println("------------------\n\n");
    	//STEP 5 - Print chains
    	/*for(String chainId: newChains.keySet()) {
    		for(DetailedMention m: newChains.get(chainId)) {
    			//System.out.println(file.getName()+"<,>"+chainId+"<,>"+m.mention().toString()+"<,>"+m.head.toString()+"<,>"+m.type());
    		}
    	}*/
    	//System.out.println();
	}
	
	String getFileContents(File file){
		
		String fullText = "";
		
		try{			
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			org.w3c.dom.Document doc = dBuilder.parse(file);
			doc.getDocumentElement().normalize();
			
			NodeList blockList = doc.getElementsByTagName("block");
		
			for (int temp = 0; temp < blockList.getLength(); temp++) {
				Node blockNode = blockList.item(temp);
				if (blockNode.getNodeType() == Node.ELEMENT_NODE) {
					Element eElement = (Element) blockNode;
					if(eElement.getAttribute("class").contentEquals("full_text")){
						NodeList textList = eElement.getElementsByTagName("p");
						for(int i=0;i< textList.getLength();i++){
							fullText = fullText + " " + textList.item(i).getTextContent();
						}
					}
				}
			}
				
	    } catch (Exception e) {
	       e.printStackTrace();
	    }
		
		return fullText;
	}
	
	public void listXMLFiles(String dataDirPath,ArrayList<File> allFiles){
		File[] files = new File(dataDirPath).listFiles();
		for (File file: files){
			if(file.isFile() && file.getName().toLowerCase().endsWith(".xml")){
				allFiles.add(file);
			}
			if(file.isDirectory()){
				listXMLFiles(dataDirPath+"/"+file.getName(),allFiles);
			}
		}
	}
	
	public static void main(String[] args) throws FileNotFoundException, UnsupportedEncodingException{
		NewChainsRuleCount data = new NewChainsRuleCount(args[0]);
		//NewChainsRuleCount data = new NewChainsRuleCount("data/input");
		ArrayList<File> allFiles = new ArrayList<File>(); 
		data.listXMLFiles(data.inputDir, allFiles);
		Map<String,List<Mention>> chains = new HashMap<>();
		for(File file: allFiles) {
			chains.clear();
			data.generateAndSelectChains(file,chains);
		}
		System.exit(0);
	}
}

