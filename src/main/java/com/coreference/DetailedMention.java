package com.coreference;

public class DetailedMention {
	
	Mention mention;
	Mention head;
	String chainId;
	String type;
	
	public DetailedMention(Mention mention, Mention head, String chainId, String type) {
		this.mention = mention;
		this.head = head;
		this.chainId = chainId;
		this.type = type;
	}
	
	String chainId() {
		return chainId;
	}

	String type() {
		return type;
	}
	
	Mention mention() {
		return mention;
	}
	
	Mention head() {
		return head;
	}
}
