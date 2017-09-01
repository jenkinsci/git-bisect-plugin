package git.gitbisect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;

public class BisectConfiguration {
	Run<?, ?> build;
	TaskListener listener;
	FilePath masterResultFile;
	FilePath localResultsFile;
	
	BisectConfiguration(Run<?,?> build, FilePath workspace, TaskListener listener, String searchIdentifier) throws IOException, InterruptedException{
    	this.build = build;
		this.listener = listener;
    	
		File rootDir = build.getParent().getRootDir();

    	masterResultFile = new FilePath(rootDir).child(searchIdentifier);
		localResultsFile = new FilePath(File.createTempFile("jenkins_git_bisect", masterResultFile.getName()));

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
			writeToLog("Copying new results file from master");
			masterResultFile.copyTo(localResultsFile);
		}
		else
		{
			writeToLog("Master does not have a previous results file, bisect will start from scratch. Is this the first run?");
			// Write something to the file which will force it's creation
			writeTo(localResultsFile, ""); 
		}
	}
	
	public String localFilePath()
	{
		// The API looks funny like this. 
		// The "getRemote" method returns the full path under the remote host.
		// In this case, the host is the local machine.
		return localResultsFile.getRemote();
	}

	public boolean hasPreviousConfiguration() throws IOException, InterruptedException {
		return masterResultFile.exists();
	}
	
	private void writeToLog(String line)
	{
		listener.getLogger().println("[GIT-BISECT]: " + line);
	}
}
