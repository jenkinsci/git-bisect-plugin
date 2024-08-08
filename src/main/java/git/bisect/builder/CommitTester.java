package git.bisect.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import git.bisect.Logger;
import git.bisect.ParametersToEnvVarsAction;
import hudson.EnvVars;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

public class CommitTester {
	@SuppressWarnings("rawtypes")
	private final class JobWrapper extends ParameterizedJobMixIn {
		@Override
		protected Job<?, ?> asJob() {
			return downstreamProj;
		}
	}

	private Run<?, ?> build;
	private Job<?, ?> downstreamProj;

	public CommitTester(Run<?, ?> build, Job<?, ?> downstreamProj) {
		this.build = build;
		this.downstreamProj = downstreamProj;
	}

	public boolean test(HashMap<String, String> bisectParameters) throws InterruptedException {
		QueueTaskFuture<? extends Run<?, ?>> buildResult = runDownStreamProject(bisectParameters);
		try {
			return getDownStreamResult(buildResult);
		} catch (ExecutionException e) {
			e.printStackTrace();
			Logger.error(
					"Downstream project threw an exception you may want to skip it this revision");
			throw new DownstreamProjectCrashed();
		}
	}
	
	public void runRecursivly(HashMap<String, String> bisectParameters) {
		runDownStreamProject(bisectParameters);
	}
	
	private QueueTaskFuture<? extends Run<?, ?>> runDownStreamProject(
								HashMap<String, String> bisectParameters) 
	{
		ArrayList<ParameterValue> combinedParameters = bubbleDownParameters(bisectParameters);

		@SuppressWarnings("unchecked")
		QueueTaskFuture<? extends Run<?, ?>> buildResult =
				new JobWrapper().scheduleBuild2(
								-1, 
								new ParametersToEnvVarsAction(bisectParameters),
								new ParametersAction(combinedParameters));
								
		return buildResult;
	}

	private boolean getDownStreamResult(QueueTaskFuture<? extends Run<?, ?>> buildResult)
			throws InterruptedException, ExecutionException 
	{
		Result downstreamResult = buildResult.get().getResult();
		if (downstreamResult == null)
		{
			Logger.log("Downstream build had failed in an unknown manner");
			throw new DownstreamProjectCrashed();
		} else if (successfull(downstreamResult)) {
			Logger.log("Downstream build was succesful");
			return true;
		} else if (aborted(downstreamResult)) {
			Logger.log("Downstream build was aborted");
			throw new DownstreamProjectCrashed();
		} else {
			Logger.log("Downstream build had failed " + downstreamResult.toString());
			return false;
		}
	}

	private boolean aborted(Result downstreamResult) {
		return downstreamResult.equals(Result.ABORTED);
	}

	private boolean successfull(Result downstreamResult) {
		return downstreamResult.equals(Result.SUCCESS);
	}

	private ArrayList<ParameterValue> bubbleDownParameters(HashMap<String,String> bisectParameters) {
		// Using HashMap to easily override and prioritize parameters
		HashMap<String, ParameterValue> combinedParameters = new HashMap<>();
		
		// Default parameters are assigned first
		// Own parameters override default parameters 
		combinedParameters.putAll(defaultJobParameters(downstreamProj));
		combinedParameters.putAll(getOwnParameters(build.getActions(ParametersAction.class)));
		
		for (Entry<String, String> param : bisectParameters.entrySet())
			combinedParameters.put(param.getKey(), new StringParameterValue(param.getKey(), param.getValue()));
		
		ArrayList<ParameterValue> result = new ArrayList<>();
		result.addAll(combinedParameters.values());
		return result;
	}

	private HashMap<String, ParameterValue> getOwnParameters(List<ParametersAction> actions) {
		HashMap<String, ParameterValue> params = new HashMap<>();
		
		for (ParametersAction parametersAction : actions) {
			for (ParameterValue parameterValue : parametersAction.getParameters()) {
				Logger.log("Aggregating parameter - " + parameterValue);
				params.put(parameterValue.getName(), parameterValue);
			}
		}
		
		return params;
	}

	private HashMap<String, ParameterValue> defaultJobParameters(Job<?, ?> downstreamProj) {
		ParametersDefinitionProperty paramDefProp = downstreamProj.getProperty(ParametersDefinitionProperty.class);

		HashMap<String, ParameterValue> defValues = new HashMap<>();

		/*
		 * This check is made ONLY if someone will call this method even if
		 * isParametrized() is false.
		 */
		if (paramDefProp == null)
			return defValues;

		/* Scan for all parameter with an associated default values */
		for (ParameterDefinition paramDefinition : paramDefProp.getParameterDefinitions()) {
			ParameterValue defaultValue = paramDefinition.getDefaultParameterValue();

			if (defaultValue != null) {
				Logger.log("Adding default value of parameter - " + defaultValue);
				defValues.put(paramDefinition.getName(), defaultValue);
			}
		}

		return defValues;
	}

	private static Job<?, ?> findDownStreamProject(String jobToRun) 
	{
		Logger.log("Looking for '" + jobToRun + "' as downstream project");
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins == null) return null;
		
		for (Job<?, ?> proj : jenkins.getAllItems(Job.class)) 
			if (proj.getFullName().equals(jobToRun))
				return proj;
		return null;
	}
	
	public static CommitTester buildFor(
								Run<?, ?> bisectBuild, 
								String jobToRun) 
	{
		Job<?, ?> downstreamProject = findDownStreamProject(jobToRun);
		
    	if (downstreamProject == null)
    		throw new DownstreamProjectNotFound();
    	
		return new CommitTester(bisectBuild, downstreamProject);
	}
}
