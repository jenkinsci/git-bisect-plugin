package git.gitbisect;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.io.Files;

import git.gitbisect.CommandsRunner.BisectionResult;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;

public class CommandsRunner {
	public static class BisectionResult
	{
		public BisectionResult(String commit, boolean isDone) {
			this.isDone = isDone;
			this.commit = commit;
		}
		
		boolean isDone;
		String commit;
	}
	public static class CommandOutput
	{
		public int exitStatus;
		public String stdout;
		public String stderr;
		
		CommandOutput(String stdout, String stderr, int exitStatus)
		{
			this.stdout = stdout;
			this.stderr = stderr;
			this.exitStatus = exitStatus;
		}
	}
	
	public static enum CommitState{
		Bad, Good
	}
	
	Run<?,?> build; 
	FilePath workspace;
	Launcher launcher;
	TaskListener listener;
	String gitCommand;
	
	public CommandsRunner(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) {
		this.build = build;
		this.workspace = workspace;
		this.launcher = launcher;
		this.listener = listener;
		this.gitCommand = getGitCommand(build, listener);
		writeToLog("Using the git command - '" + gitCommand + "'");
	}
	
	public CommandOutput getBisectionLog() throws IOException, InterruptedException {
		return runCommand("bisect", "log");
	}
	
	public BisectionResult markCommitAs(String commit, CommitState state_) throws IOException, InterruptedException
	{
		String state = "good";
		if (state_ == CommitState.Bad)
			state = "bad";
		
		writeToLog("Marking commit " + commit + " as - " + state);
		CommandOutput bisectOutput = runCommand("bisect", state, commit);
		return parseBisectOutput(bisectOutput);
	}
	
	public void resetBisection() throws IOException, InterruptedException
	{
		runCommandAndForget("bisect", "reset");
	}
	
	public void startBisection() throws IOException, InterruptedException
	{
		runCommandAndForget("bisect", "start", "--no-checkout");
	}
	
	public BisectionResult bisectFromFile(String path) throws IOException, InterruptedException
	{
		String completionLine = findCompletionToken(path);
		if (completionLine != null)
			return new BisectionResult(revisionFromLine(completionLine), true);
		
		CommandOutput bisectOutput = runCommand("bisect", "replay", path);
		return parseBisectOutput(bisectOutput);
	}

	private String revisionFromLine(String completionLine) {
		int revStart = completionLine.indexOf("[") + 1;
		int revEnd = completionLine.indexOf("]");
		return completionLine.substring(revStart, revEnd);
	}

	private String findCompletionToken(String path) throws IOException, InterruptedException {
		List<String> bisectLines = Files.readLines(new File(path), Charset.defaultCharset());
		
		for (String line : bisectLines)
		{
			if (hasCompletionToken(line))
				return line;
		}
		
		return null;
	}
	
	public boolean checkExistance(String commit) throws IOException, InterruptedException {
		String[] checkCommandArgs = { "cat-file", "-e", commit + "^{commit}" };
		CommandOutput result = runCommandImpl(checkCommandArgs);
		boolean exists = result.exitStatus == 0;
		if (!exists)
		{
			writeToLog("The commit - " + commit + " does not exist in the repository. (did you forget adding the remote name?)");
			writeToLog(
					"The command exited with exit code = " + 
					result.exitStatus + " and stderror was "  + 
					result.stderr);
		}
		return exists;
	}
	
	private BisectionResult parseBisectOutput(CommandOutput bisectOutput) throws IOException, InterruptedException {
		boolean isDone = hasCompletionToken(bisectOutput.stdout);
		
		String nextCommit = getNextCommit();
		return new BisectionResult(nextCommit, isDone);
	}
	
	private boolean hasCompletionToken(String line)
	{
		return line.contains("first bad commit");
	}
	
	private String getNextCommit() throws IOException, InterruptedException
	{
		return runCommand("rev-parse", "BISECT_HEAD").stdout;
	}
	
	private void runCommandAndForget(String... cmds)
			throws IOException, InterruptedException {
		CommandOutput result = runCommand(cmds);
		
		if (result.exitStatus != 0)
			writeToLog("Last command failed");
		writeToLog("stdout = " + result.stdout);
		writeToLog("stderr = " + result.stderr);
	}
	
	private CommandOutput runCommand(String... cmds) throws IOException, InterruptedException
	{
		CommandOutput result = runCommandImpl(cmds);
		
		if (result.exitStatus != 0)
		{
			writeResultToLog(result, cmds);
		}
		
		return result;
	}
	
	private CommandOutput runCommandImpl(String... cmds) throws IOException, InterruptedException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream capturedErrors = new ByteArrayOutputStream();
		List<String> args = new ArrayList<>();
		args.add(gitCommand);
		args.addAll(Arrays.asList(cmds));
		
		int exitStatus = launcher.launch()
		.cmds(args)
		.envs(build.getEnvironment(listener))
		.pwd(workspace)
		.stdout(out)
		.stderr(capturedErrors)
		.join();
		
		return new CommandOutput(
				out.toString().trim(), 
				capturedErrors.toString().trim(), 
				exitStatus);
	}
	
	private void writeToLog(String line)
	{
		listener.getLogger().println("[GIT-BISECT]: " + line);
	}
	
	private void writeResultToLog(CommandOutput result, String... cmds) {
		String command = gitCommand + " ";
		for (String s : cmds)
			command += s + " ";
		
		writeToLog(
			"The following command:\n" + 
			"'" + command + "'\n" + 
			"exited with error code '" + String.valueOf(result.exitStatus) + "'\n" + 
			"stderr contained: '\n" + result.stderr + "'\n" + 
			"stdout contained: '\n" + result.stdout + "'");
		
		throw new RuntimeException(
				"Could not run the command - \n" + 
				command + " it failed with the following error - \n" + 
				result.stderr);
	}
	
	private String getGitCommand(Run<?, ?> build, TaskListener listener) {
		AbstractProject<?, ?> proj = (AbstractProject<?, ?>)build.getParent();
		GitSCM g = (GitSCM)proj.getScm();
		return g.getGitExe(null, listener);
	}
}
