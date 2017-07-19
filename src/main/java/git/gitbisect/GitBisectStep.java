package git.gitbisect;

import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousStepExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

public class GitBisectStep extends Step {

	private static class Execution extends AbstractSynchronousStepExecution<Boolean>
	{

		@Override
		protected Boolean run() throws Exception {
			// TODO Auto-generated method stub
			return null;
		}

		
	}
	
	@Override
	public StepExecution start(StepContext context) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}

}
