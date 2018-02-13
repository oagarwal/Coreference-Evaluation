package com.training;

public class Mention {

	String mention;
	int sentNum;
	int startTokenInd;
	int endTokenInd;
	
	public Mention(String mention, int sentNum, int startTokenInd, int endTokenInd) {
		this.mention =  mention;
		this.sentNum = sentNum;
		this.startTokenInd = startTokenInd;
		this.endTokenInd = endTokenInd;
	}
	
	public String mention() {
		return mention;
	}
	
	public int sentNum() {
		return sentNum;
	}
	
	public int startTokenInd() {
		return startTokenInd;
	}
	
	public int endTokenInd() {
		return endTokenInd;
	}
	
	public String toString() {
		return mention + "<,>" + sentNum + "<,>" + startTokenInd + "<,>" + endTokenInd;
	}
	
}
