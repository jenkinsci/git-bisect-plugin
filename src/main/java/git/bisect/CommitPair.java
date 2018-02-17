package git.bisect;

public class CommitPair{
	public CommitPair(String goodCommit, String badCommit) {
		this.goodCommit = goodCommit;
		this.badCommit = badCommit;
	}
	
	public String goodCommit;
	public String badCommit;
}