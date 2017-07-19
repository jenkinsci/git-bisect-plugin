package git.gitbisect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link GitBisectBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked. 
 *
 * @author Kohsuke Kawaguchi
 */
public class GitBisectBuilder extends Builder implements SimpleBuildStep {

    final String jobToRun;
	final String goodStartCommit;
	final String badEndCommit;
	final String searchIdentifier;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public GitBisectBuilder(String jobToRun, String goodStartCommit, String badEndCommit, String searchIdentifier) {
		this.jobToRun = jobToRun;
		this.goodStartCommit = goodStartCommit;
		this.badEndCommit = badEndCommit;
		this.searchIdentifier = searchIdentifier;
    }

    transient Run<?,?> build; 
    transient FilePath workspace;
    transient Launcher launcher;
    transient TaskListener listener;
    transient FilePath masterResultFile;
    transient FilePath localResultFile;
	transient AbstractProject<?, ?> downstreamProj;
	
	protected FilePath MakeReplayFile(FilePath masterWorkspace, FilePath localWorkspace) throws IOException, InterruptedException
	{
		FilePath localTempFile = localWorkspace.createTempFile(searchIdentifier, null);
		masterWorkspace.getParent().child(searchIdentifier).copyTo(localTempFile);
		return localTempFile;
	}
	
    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException{
    	this.build = build;
    	this.workspace = workspace;
    	this.launcher = launcher;
    	this.listener = listener;
    	
    	downstreamProj = findDownStreamProject();
    	
    	if (downstreamProj == null)
    		throw new DownstreamProjectNotFound();
    	
    	File rootDir = build.getParent().getRootDir();
    	
    	masterResultFile = new FilePath(rootDir).child(searchIdentifier);
    	localResultFile = workspace.child(searchIdentifier);
		copyResultsFromMaster();
    	
		writeToLog("Initializing");
		BisectionResult bisectResult = getNextBisectPoint();
		
		while (!bisectResult.isDone)
			bisectResult = run(bisectResult.commit);
		
		writeToLog("Bisect completed, wanted commit is - " + bisectResult.commit);
    }

	private BisectionResult run(String commit) throws InterruptedException, IOException {
		writeToLog("Running against commit - " + commit);
		boolean runResult = testCommit(commit);
		BisectionResult bisectResult = markCommitAs(commit, runResult ? "good" : "bad");
		copyResultsToMaster();
		return bisectResult;
	}

    private void copyResultsToMaster()
			throws IOException, InterruptedException {
    	writeToLog("Copying results to master");
    	localResultFile.write(runCommand("git", "bisect", "log"), "ASCII");
		localResultFile.copyTo(masterResultFile);
	}
    
	private void copyResultsFromMaster()
			throws IOException, InterruptedException {
		writeToLog("Deleting old results file");
		localResultFile.delete();
		if (masterResultFile.exists())
		{
			writeToLog("Copying new results file from master");
			masterResultFile.copyTo(localResultFile);
		}
		else
		{
			writeToLog("Master does not have a previous results file, bisect will start from scratch. Is this the first run?");
		}
	}

	private AbstractProject<?, ?> findDownStreamProject() 
	{
		writeToLog("Looking for '" + jobToRun + "' as downstream project");
		for (AbstractProject<?, ?> proj : Jenkins.getInstance().getAllItems(AbstractProject.class)) 
			if (proj.getName().equals(jobToRun))
				return proj;
		return null;
	}

	class BisectionResult
	{
		public BisectionResult(String commit, boolean isDone) {
			this.isDone = isDone;
			this.commit = commit;
		}
		
		boolean isDone;
		String commit;
	}
	
	private boolean testCommit(String commitToTest) throws InterruptedException {
		writeToLog("Running downstream project with commit = " + commitToTest);
		QueueTaskFuture<? extends AbstractBuild<?, ?>> buildResult = runDownStreamProject(build, commitToTest);
		try 
		{
			return getDownStreamResult(buildResult, commitToTest);
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			listener.error("Downstream project threw an exception for commit " + commitToTest + " you may want to skip it");
			throw new DownstreamProjectCrashed();
		}
	}

	private boolean getDownStreamResult(QueueTaskFuture<? extends AbstractBuild<?, ?>> buildResult, String commitToTest)
			throws InterruptedException, ExecutionException {
		AbstractBuild<?, ?> abstractBuild = buildResult.get();
		if (abstractBuild.getResult().equals(Result.SUCCESS))
		{
			this.writeToLog("Downstream build was succesful");
			return true;
		}
		else
		{
			this.writeToLog("Result was not SUCCESS... " + abstractBuild.getResult().toString());
			return false;
		}
	}

	private QueueTaskFuture<? extends AbstractBuild<?, ?>> runDownStreamProject(Run<?, ?> build, String commitId) {
		ArrayList<ParameterValue> combinedParameters = bubbleDownParameters(build, commitId);		    	
		
		QueueTaskFuture<? extends AbstractBuild<?, ?>> buildResult = downstreamProj.scheduleBuild2(-1, new Cause.UpstreamCause(build), new ParametersAction(combinedParameters));
		return buildResult;
	}

	private ArrayList<ParameterValue> bubbleDownParameters(Run<?, ?> build, String commitId) {
		ArrayList<ParameterValue> combinedParameters = new ArrayList<>();
		addParameters(build.getActions(ParametersAction.class), combinedParameters);
		combinedParameters.addAll(defaultJobParameters(downstreamProj));
		combinedParameters.add(new StringParameterValue("COMMIT", commitId));
		return combinedParameters;
	}

	private void addParameters(List<ParametersAction> actions, ArrayList<ParameterValue> combinedParameters) {
		for (ParametersAction parametersAction : actions) {
			for (ParameterValue parameterValue : parametersAction.getParameters()) {
				writeToLog("Aggregating parameter - " + parameterValue);
				combinedParameters.add(parameterValue);
			}
		}
	}
	
	private ArrayList<ParameterValue> defaultJobParameters(AbstractProject<?, ?> downstreamProj) {
		ParametersDefinitionProperty paramDefProp = downstreamProj.getProperty(ParametersDefinitionProperty.class);
		
		ArrayList<ParameterValue> defValues = new ArrayList<ParameterValue>();

        /*
         * This check is made ONLY if someone will call this method even if isParametrized() is false.
         */
        if(paramDefProp == null)
            return defValues;

        /* Scan for all parameter with an associated default values */
        for(ParameterDefinition paramDefinition : paramDefProp.getParameterDefinitions())
        {
           ParameterValue defaultValue  = paramDefinition.getDefaultParameterValue();

            if(defaultValue != null)
            {
				writeToLog("Adding default value of parameter - " + defaultValue);
                defValues.add(defaultValue);
            }
        }
        
        return defValues;
	}

	private BisectionResult markCommitAs(String commit, String state) throws IOException, InterruptedException {
		writeToLog("Marking commit as - " + state);
		String result = runCommand("git", "bisect", state, commit);
		return getCommitFromBisectOutput(result);
	}

	private BisectionResult getNextBisectPoint() throws IOException, InterruptedException {
		runCommandAndForget("git", "bisect", "reset");
		runCommandAndForget("git", "bisect", "start", "--no-checkout");
        
        String resultWithNextCommit = getGitBisectResult();
		return getCommitFromBisectOutput(resultWithNextCommit);
	}

	private BisectionResult getCommitFromBisectOutput(String resultWithNextCommit) {
		writeToLog(resultWithNextCommit);
		
		int commitStart, commitEnd;
		boolean isDone = resultWithNextCommit.contains("is the first bad commit");
		
		if (isDone)
		{
			commitStart = 0;
			commitEnd = resultWithNextCommit.indexOf(' ');
		}
		else
		{
			commitStart = resultWithNextCommit.indexOf("[") + 1;
			commitEnd = resultWithNextCommit.indexOf("]") - 1;
		}
		
		String nextCommit = resultWithNextCommit.subSequence(commitStart, commitEnd).toString();
		return new BisectionResult(nextCommit, isDone);
	}

	private String getGitBisectResult() throws IOException, InterruptedException {
		if (localResultFile.exists())
			return bisectWithPreviousResults();
        else
        	return bisectWithGivenInput();
	}

	private String bisectWithPreviousResults() throws IOException, InterruptedException {
		return runCommand("git", "bisect", "replay", localResultFile.getName());
	}

	private String bisectWithGivenInput() throws IOException, InterruptedException {
		runCommandAndForget("git", "bisect", "bad", badEndCommit);
		return runCommand("git", "bisect", "good", goodStartCommit);
	}

	private void runCommandAndForget(String... cmds)
			throws IOException, InterruptedException {
		runCommand(listener.getLogger(), cmds);
	}
	
	private String runCommand(String... cmds)
			throws IOException, InterruptedException {
		ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
		runCommand(capturedOutput, cmds);
		return capturedOutput.toString();
	}
	
	private void runCommand(OutputStream out, String... cmds) throws IOException, InterruptedException
	{
		launcher.launch()
		.cmds(cmds)
		.envs(build.getEnvironment(listener))
		.pwd(workspace)
		.stdout(out)
		.start();
		
	}

	private void writeToLog(String line)
	{
		listener.getLogger().print("[GIT-BISECT]: ");
		listener.getLogger().println(line);
	}
	
    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public String getJobToRun() {
		return jobToRun;
	}

	public String getBadEndCommit() {
		return badEndCommit;
	}

	public String getGoodStartCommit() {
		return goodStartCommit;
	}

	/**
     * Descriptor for {@link GitBisectBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

//        public FormValidation doCheckRepository(@QueryParameter String value)
//        {
//            if (value.length() == 0)
//                return FormValidation.error("Repository can't be empty");
//            return FormValidation.ok();
//        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        public String getDisplayName() {
            return "Git Bisect Configurations";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            
            return super.configure(req,formData);
        }
    }
}

