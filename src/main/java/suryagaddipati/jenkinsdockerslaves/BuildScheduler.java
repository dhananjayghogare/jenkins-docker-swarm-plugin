package suryagaddipati.jenkinsdockerslaves;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;

import java.io.IOException;


public class BuildScheduler {
    public static void scheduleBuild(final Queue.BuildableItem bi) {
        try (ACLContext _ = ACL.as(ACL.SYSTEM)) {
            final DockerLabelAssignmentAction action = createLabelAssignmentAction();
            final DockerSlave node = new DockerSlave(bi, action.getLabel().toString());
            setToInProgress(bi);
            bi.replaceAction(new DockerSlaveInfo(true));
            bi.replaceAction(action);
            Computer.threadPoolForRemoting.submit((Runnable) () -> {
                JenkinsHacks.addNodeWithoutQueueLock(node);
//                try {
//                    Jenkins.getInstance().addNode(node); //locks queue
//                } catch (final IOException e) {
//                    e.printStackTrace();
//                }
            });

        } catch (final IOException e) {
            e.printStackTrace();
        } catch (final Descriptor.FormException e) {
            e.printStackTrace();
        }
    }

    private static DockerLabelAssignmentAction createLabelAssignmentAction() {
        try {
            Thread.sleep(5, 10);
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }

        final String id = System.nanoTime() + "";
        final Label label = new DockerMachineLabel(id);
        return new DockerLabelAssignmentAction(label);
    }

    private static void setToInProgress(final Queue.BuildableItem bi) {
        final DockerSlaveInfo slaveInfoAction = bi.getAction(DockerSlaveInfo.class);
        if (slaveInfoAction != null) {
            slaveInfoAction.setProvisioningInProgress(true);
        } else {
            bi.replaceAction(new DockerSlaveInfo(true));
        }
    }

}
