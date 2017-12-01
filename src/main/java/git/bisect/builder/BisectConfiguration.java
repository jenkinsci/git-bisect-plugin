package git.bisect.builder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;

public class BisectConfiguration {
	TaskListener listener;
	FilePath masterResultFile;
	FilePath localResultsFile;
	
	public BisectConfiguration(Run<?,?> build, FilePath workspace, TaskListener listener, String searchIdentifier) throws IOException, InterruptedException{
		this.listener = listener;
    	
		File rootDir = build.getParent().getRootDir();

    	masterResultFile = new FilePath(rootDir).child(searchIdentifier);
		localResultsFile = workspace.createTempFile("jenkins_git_bisect", masterResultFile.getName());

		writeToLog("Local file to be used - " + localResultsFile.getRemote());
		
		fetchFromMaster();
	}
	
	public void saveContent(String data) throws IOException, InterruptedException	{
		writeTo(localResultsFile, data);
		localResultsFile.copyTo(masterResultFile);
	}

	private void writeTo(FilePath file, String data) throws IOException, InterruptedException {
		file.write(data, Charset.defaultCharset().name());
	}
	
	public void fetchFromMaster() throws IOException, InterruptedException {
		if (hasPreviousConfiguration())
		{
			writeToLog("Copying latest results file from master to " + localResultsFile.getRemote());
			masterResultFile.copyTo(localResultsFile);
		}
		else
		{
			writeToLog("Master does not have a previous results file, bisect will start from scratch. Is this the first run?");
			// Write something to the file which will force it's creation
			writeTo(localResultsFile, ""); 
		}
	}
	
	public FilePath localFile()
	{
		return localResultsFile;
	}
	
	public void cleanup() throws IOException, InterruptedException
	{
		localResultsFile.delete();
	}

	public boolean hasPreviousConfiguration() throws IOException, InterruptedException {
		return masterResultFile.exists() && 
			   !masterResultFile.readToString().isEmpty();
	}
	
	private void writeToLog(String line)
	{
		listener.getLogger().println("[GIT-BISECT]: " + line);
	}
}
