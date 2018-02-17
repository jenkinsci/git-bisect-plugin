package git.bisect.on_failure;
import java.io.IOException;
import java.util.HashMap;

import org.kohsuke.stapler.DataBoundConstructor;

import git.bisect.CommitPair;
import git.bisect.Logger;
import git.bisect.builder.CommandsRunner;
import git.bisect.builder.CommandsRunner.BisectionResult;
import git.bisect.builder.CommandsRunner.CommitState;
import git.bisect.builder.CommitTester;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import jenkins.tasks.SimpleBuildStep;

public class GitBisectOnFailure extends Notifier implements SimpleBuildStep {

	private static final String GIT_PREV_GOOD = "GIT_PREVIOUS_SUCCESSFUL_COMMIT";
	private static final String GIT_COMMIT = "GIT_COMMIT";
	private static final String BISECT_BAD_COMMIT = "BISECT_BAD_COMMIT";
	private static final String BISECT_GOOD_COMMIT = "BISECT_GOOD_COMMIT";
	private static final String BISECT_IDENTIFIER = "BISECT_INTERNAL_SEARCH_IDENTIFIER";

	transient private Run<?, ?> build;
	transient private EnvVars env;
	transient private CommitTester commitTester;
	transient private CommandsRunner cmd;
	
	private String gitCommand;
	private String revisionParameterName;
	private boolean overrideGitCommand;

	
	@DataBoundConstructor
    public GitBisectOnFailure(String gitCommand, String revisionParameterName, boolean overrideGitCommand) {
		this.overrideGitCommand = overrideGitCommand;
		this.gitCommand = gitCommand;
		this.revisionParameterName = revisionParameterName;
    }
	
	public String getRevisionParameterName() {
		return revisionParameterName;
	}
	
	public boolean getOverrideGitCommand() {
		return overrideGitCommand;
	}
	
	public String getGitCommand() {
		return gitCommand;
	}
    
	@Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
		try
		{
			this.build = build;
			
			Logger.initializeLogger(listener);
			env = build.getEnvironment(listener);
			
			commitTester = new CommitTester(build, build.getParent());
			cmd = new CommandsRunner(build, workspace, launcher, listener, gitCommand);
			
			performBisection();
		}
		catch (Exception e)
		{
			Logger.log("Git bisection failed due to an exception");
			Logger.printStackTrace(e);
		}
    }

	private void performBisection() throws IOException, InterruptedException {
		if (currentlyBisecting()) 
			continueBisection();
		else if (currentCommitFailed())
			startBisection();
	}

	private void continueBisection() throws IOException, InterruptedException {
		CommitPair previousCommits = new CommitPair(
											env.get(BISECT_GOOD_COMMIT), 
											env.get(BISECT_BAD_COMMIT));

		Logger.log("Continueing bisection with - good commit: " + 
					previousCommits.goodCommit + ", and bad commit: " + 
					previousCommits.badCommit);
		
		BisectionResult result = initBisection(previousCommits);

		if (result.isDone)
		{
			Logger.log("Found the first bad commit at - " + result.commit);
			return;
		}

		if (currentCommitFailed())
			result = cmd.markCommitAs(currentCommit(), CommitState.Bad);
		else
			result = cmd.markCommitAs(currentCommit(), CommitState.Good);
		
		runNextStep(result, previousCommits);
	}

	private String currentCommit() {
		return env.get(GIT_COMMIT);
	}

	private void runNextStep(BisectionResult result, CommitPair prevResults) {
		if (result.isDone)
		{
			Logger.log("Found the first bad commit at - " + result.commit);
			return;
		}
		
		Logger.log("Next commit to be tested - " + result.commit);
		
		CommitPair nextPair = getNextPair(prevResults);
		commitTester.runRecursivly(withBisectParams(result, nextPair));
	}

	
	private CommitPair getNextPair(CommitPair prevResults) {
		String badCommit = currentCommitFailed() ? currentCommit() : prevResults.badCommit;
		String goodCommit = currentCommitFailed() ? prevResults.goodCommit : currentCommit(); 
		
		return new CommitPair(goodCommit, badCommit);
	}

	private void startBisection() throws IOException, InterruptedException {
		CommitPair startingStates = new CommitPair(prevGoodCommit(), currentCommit());

		if (startingStates.goodCommit == null)
		{
			Logger.error("Git bisection can't detect the previous good commit," + 
						 " the environment variable" + GIT_PREV_GOOD + " did not exist... \n" + 
						 "You may want to run the bisection using an external job, " + 
						 "Sorry for wasting your time and resources :(");
			
			throw new RuntimeException();
		}
		
		Logger.log("Starting bisection after failure with - good commit: " + 
					startingStates.goodCommit + ", and bad commit: " + 
					startingStates.badCommit);
		
		BisectionResult result = initBisection(startingStates);
		runNextStep(result, startingStates);
	}

	private String prevGoodCommit() {
		return env.get(GIT_PREV_GOOD);
	}
	
	private BisectionResult initBisection(CommitPair prevRunResult) throws IOException, InterruptedException {
		validateCommits(prevRunResult);
		cmd.resetBisection();
		cmd.startBisection();
		
		BisectionResult result;
		result = cmd.markCommitAs(prevRunResult.goodCommit, CommitState.Good);
		result = cmd.markCommitAs(prevRunResult.badCommit, CommitState.Bad);
		
		return result;
	}

	private void validateCommits(CommitPair pair) throws IOException, InterruptedException {
		boolean valid = cmd.checkExistance(pair.goodCommit) &&
						cmd.checkExistance(pair.badCommit);
		
		if (valid == false)
			throw new RuntimeException("Can't start bisecting, can't query repository for relevant commits");
	}

	private boolean currentCommitFailed() {
		return build.getResult() == Result.FAILURE;
	}
	
	@Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    	public DescriptorImpl() {
    		super(GitBisectOnFailure.class);
			load();
    	}
    	
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Git Bisect On Failure";
        }
    }
    
    @Override
    public boolean needsToRunAfterFinalized() {
    	return true;
    }

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}
	
	public boolean currentlyBisecting() {
		return env.get(BISECT_IDENTIFIER) != null;
	}
	
	public HashMap<String, String> withBisectParams(BisectionResult result, CommitPair knownCommits) {
		HashMap<String, String> bisectParams = new HashMap<String, String>();
		bisectParams.put(revisionParameterName, result.commit);
		bisectParams.put(BISECT_GOOD_COMMIT, knownCommits.goodCommit);
		bisectParams.put(BISECT_BAD_COMMIT, knownCommits.badCommit);
		bisectParams.put(BISECT_IDENTIFIER, "TRUE");
		return bisectParams;
	}
}

