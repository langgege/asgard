/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard.deployment

import com.amazonaws.services.simpleworkflow.flow.ActivitySchedulingOptions
import com.amazonaws.services.simpleworkflow.flow.core.Promise
import com.amazonaws.services.simpleworkflow.flow.interceptors.ExponentialRetryPolicy
import com.amazonaws.services.simpleworkflow.flow.interceptors.RetryPolicy
import com.netflix.asgard.DiscoveryService
import com.netflix.asgard.GlobalSwfWorkflowAttributes
import com.netflix.asgard.Relationships
import com.netflix.asgard.UserContext
import com.netflix.asgard.model.AutoScalingGroupBeanOptions
import com.netflix.asgard.model.LaunchConfigurationBeanOptions
import com.netflix.asgard.push.PushException
import com.netflix.glisten.DoTry
import com.netflix.glisten.WorkflowOperations
import com.netflix.glisten.WorkflowOperator
import com.netflix.glisten.impl.swf.SwfWorkflowOperations

class DeploymentWorkflowImpl implements DeploymentWorkflow, WorkflowOperator<DeploymentActivities> {

    @Delegate WorkflowOperations<DeploymentActivities> workflowOperations = SwfWorkflowOperations.of(
            DeploymentActivities, new ActivitySchedulingOptions(taskList: GlobalSwfWorkflowAttributes.taskList))

    Closure<String> unit = { int count, String unitName ->
        count == 1 ? "1 ${unitName}" : "${count} ${unitName}s" as String
    }

    Closure<Integer> minutesToSeconds = { it * 60 }

    @Override
    void deploy(UserContext userContext, DeploymentWorkflowOptions deploymentOptions,
                LaunchConfigurationBeanOptions lcInputs, AutoScalingGroupBeanOptions asgInputs) {
        if (deploymentOptions.delayDurationMinutes) {
            status "Waiting ${unit(deploymentOptions.delayDurationMinutes, 'minute')} before starting deployment."
        }
        Promise<Void> delay = timer(minutesToSeconds(deploymentOptions.delayDurationMinutes), 'delay')
        Promise<AsgDeploymentNames> asgDeploymentNamesPromise = waitFor(delay) {
            promiseFor(activities.getAsgDeploymentNames(userContext, deploymentOptions.clusterName))
        }
        Throwable rollbackCause = null
        Promise<Void> deploymentComplete = waitFor(asgDeploymentNamesPromise) { AsgDeploymentNames asgDeploymentNames ->
            status "Starting deployment for Cluster '${deploymentOptions.clusterName}'."
            doTry {
                AutoScalingGroupBeanOptions nextAsgTemplate = AutoScalingGroupBeanOptions.from(asgInputs)
                nextAsgTemplate.with {
                    autoScalingGroupName = asgDeploymentNames.nextAsgName
                    launchConfigurationName = asgDeploymentNames.nextLaunchConfigName
                }
                Promise<LaunchConfigurationBeanOptions> nextLcTemplateConstructed = promiseFor(activities.
                        constructLaunchConfigForNextAsg(userContext, nextAsgTemplate, lcInputs))
                waitFor(nextLcTemplateConstructed) { LaunchConfigurationBeanOptions nextLcTemplate ->
                    startDeployment(userContext, deploymentOptions, asgDeploymentNames, nextAsgTemplate, nextLcTemplate)
                }
            } withCatch { Throwable e ->
                rollbackCause = e
                rollback(userContext, asgDeploymentNames, deploymentOptions.notificationDestination, rollbackCause)
                promiseFor(false)
            } result
        }
        waitFor(deploymentComplete) {
            if (rollbackCause) {
                if (rollbackCause.getClass() == PushException) {
                    status "Deployment was rolled back. ${rollbackCause.message}"
                } else {
                    status "Deployment was rolled back due to error: ${rollbackCause}"
                }
            } else {
                status 'Deployment was successful.'
            }
        }
    }

    private Promise<Void> startDeployment(UserContext userContext, DeploymentWorkflowOptions deploymentOptions,
            AsgDeploymentNames asgDeploymentNames, AutoScalingGroupBeanOptions nextAsgTemplate,
            LaunchConfigurationBeanOptions nextLcTemplate) {
        status "Creating Launch Configuration '${asgDeploymentNames.nextLaunchConfigName}'."
        Promise<String> launchConfigCreated = promiseFor(activities.createLaunchConfigForNextAsg(userContext,
                nextAsgTemplate, nextLcTemplate))
        Promise<Void> asgCreated = waitFor(launchConfigCreated) {
            status "Creating Auto Scaling Group '${asgDeploymentNames.nextAsgName}' initially with 0 instances."
            waitFor(activities.createNextAsgForClusterWithoutInstances(userContext, nextAsgTemplate)) {
                status 'Copying Scaling Policies and Scheduled Actions.'
                Promise<Integer> scalingPolicyCount = promiseFor(
                        activities.copyScalingPolicies(userContext, asgDeploymentNames))
                Promise<Integer> scheduledActionCount = promiseFor(
                        activities.copyScheduledActions(userContext, asgDeploymentNames))
                allPromises(scalingPolicyCount, scheduledActionCount)
            }
        }

        Promise<Boolean> scaleToDesiredCapacity = waitFor(asgCreated) {
            status "New ASG '${asgDeploymentNames.nextAsgName}' was successfully created."
            if (!deploymentOptions.doCanary) {
                return promiseFor(true)
            }
            String operationDescription = 'canary capacity'
            int canaryCapacity = deploymentOptions.canaryCapacity
            Promise<Boolean> scaleAsgPromise = scaleAsg(userContext, asgDeploymentNames.nextAsgName,
                    deploymentOptions.canaryStartUpTimeoutMinutes, canaryCapacity, canaryCapacity,
                    canaryCapacity, operationDescription)
            waitFor(scaleAsgPromise) {
                determineWhetherToProceedToNextStep(asgDeploymentNames.nextAsgName,
                        deploymentOptions.canaryJudgmentPeriodMinutes, deploymentOptions.notificationDestination,
                        deploymentOptions.scaleUp, operationDescription)
            }
        }

        Promise<Boolean> disablePreviousAsg = waitFor(scaleToDesiredCapacity) {
            if (!it) { return promiseFor(false) }
            String operationDescription = 'full capacity'
            Promise<Boolean> scaleAsgPromise = scaleAsg(userContext, asgDeploymentNames.nextAsgName,
                    deploymentOptions.desiredCapacityStartUpTimeoutMinutes, nextAsgTemplate.minSize,
                    nextAsgTemplate.desiredCapacity, nextAsgTemplate.maxSize, operationDescription)
            waitFor(scaleAsgPromise) {
                determineWhetherToProceedToNextStep(asgDeploymentNames.nextAsgName,
                        deploymentOptions.desiredCapacityJudgmentPeriodMinutes,
                        deploymentOptions.notificationDestination, deploymentOptions.disablePreviousAsg,
                        operationDescription)
            }
        }

        Promise<Boolean> isPreviousAsgDisabled = waitFor(disablePreviousAsg) {
            if (!it) { return promiseFor(false) }
            if (deploymentOptions.disablePreviousAsg) {
                String previousAsgName = asgDeploymentNames.previousAsgName
                status "Disabling ASG '${previousAsgName}'."
                activities.disableAsg(userContext, previousAsgName)
            }
            Promise.asPromise(true)
        }

        waitFor(isPreviousAsgDisabled) {
            String previousAsgName = asgDeploymentNames.previousAsgName
            if (!it) {
                status "ASG '${previousAsgName}' was not disabled. The new ASG is not taking full traffic."
            } else {
                long secondsToWaitAfterEurekaChange = DiscoveryService.SECONDS_TO_WAIT_AFTER_EUREKA_CHANGE
                status "Waiting ${secondsToWaitAfterEurekaChange} seconds for clients to stop using instances."
                Promise<Void> waitAfterEurekaChange = timer(secondsToWaitAfterEurekaChange, 'waitAfterEurekaChange')
                waitFor(waitAfterEurekaChange) {
                    Promise<Boolean> deleteAsg = determineWhetherToProceedToNextStep(
                            asgDeploymentNames.nextAsgName, deploymentOptions.fullTrafficJudgmentPeriodMinutes,
                            deploymentOptions.notificationDestination, deploymentOptions.deletePreviousAsg,
                            'full traffic')
                    waitFor(deleteAsg) {
                        if (it) {
                            activities.deleteAsg(userContext, previousAsgName)
                            status "Deleting ASG '${previousAsgName}'."
                        }
                        Promise.Void()
                    }
                }
            }
        }
    }

    private Promise<Boolean> scaleAsg(UserContext userContext, String asgName,
            int startupLimitMinutes, int min, int capacity, int max, String operationDescription) {
        status "Scaling '${asgName}' to ${operationDescription} (${unit(capacity, 'instance')})."
        activities.resizeAsg(userContext, asgName, min, capacity, max)
        status "Waiting up to ${unit(startupLimitMinutes, 'minute')} for ${unit(capacity, 'instance')}."
        RetryPolicy retryPolicy = new ExponentialRetryPolicy(30L).withBackoffCoefficient(1).
                withExceptionsToRetry([PushException])
        DoTry<Void> asgIsOperational = doTry {
            retry(retryPolicy) {
                waitFor(activities.reasonAsgIsNotOperational(userContext, asgName, capacity)) { String reason ->
                    if (reason) {
                        throw new PushException(reason)
                    }
                    Promise.Void()
                }
            }
        }
        DoTry<Void> startupTimeout = cancelableTimer(minutesToSeconds(startupLimitMinutes), 'startupTimeout')
        waitFor(anyPromises(startupTimeout.result, asgIsOperational.result)) {
            if (asgIsOperational.result.ready) {
                startupTimeout.cancel(null)
                promiseFor(true)
            } else {
                asgIsOperational.cancel(null)
                waitFor(activities.reasonAsgIsNotOperational(userContext, asgName, capacity)) {
                    if (it) {
                        String msg = "ASG '${asgName}' was not at capacity after " +
                                "${unit(startupLimitMinutes, 'minute')}."
                        throw new PushException(msg)
                    }
                    promiseFor(true)
                }
            }
        }
    }

    private Promise<Boolean> determineWhetherToProceedToNextStep(String asgName, int judgmentPeriodMinutes,
            String notificationDestination, ProceedPreference continueWithNextStep, String operationDescription) {
        if (continueWithNextStep == ProceedPreference.Yes) { return promiseFor(true) }
        if (continueWithNextStep == ProceedPreference.No) { return promiseFor(false) }
        if (judgmentPeriodMinutes) {
            status "ASG will be evaluated for up to ${unit(judgmentPeriodMinutes, 'minute')}."
        }
        String judgmentMessage = "${operationDescription.capitalize()} judgment period for ASG '${asgName}' has begun."
        Promise<Boolean> proceed = promiseFor(activities.askIfDeploymentShouldProceed(notificationDestination,
                    asgName, judgmentMessage))
        status judgmentMessage
        DoTry<Void> sendNotificationAtJudgmentTimeout = doTry {
            Promise<Void> judgmentTimeout = timer(minutesToSeconds(judgmentPeriodMinutes), 'judgmentTimeout')
            waitFor(judgmentTimeout) {
                String clusterName = Relationships.clusterFromGroupName(asgName)
                String subject = "Judgement period for ASG '${asgName}' has ended."
                String message = 'Please make a decision to proceed or roll back.'
                activities.sendNotification(notificationDestination, clusterName, subject, message)
                Promise.Void()
            }
        }
        waitFor(proceed) {
            sendNotificationAtJudgmentTimeout.cancel(null)
            if (it) {
                promiseFor(true)
            } else {
                throw new PushException("Judge decided ASG '${asgName}' was not operational.")
            }
        }
    }

    private void rollback(UserContext userContext, AsgDeploymentNames asgDeploymentNames,
            String notificationDestination, Throwable rollbackCause) {
        String action = "Rolling back to '${asgDeploymentNames.previousAsgName}'."
        status action
        String clusterName = Relationships.clusterFromGroupName(asgDeploymentNames.nextAsgName)
        String message = """\
        Auto Scaling Group '${asgDeploymentNames.nextAsgName}' is ${rollbackCause ? 'not ' : ''}operational.
        ${rollbackCause.message}""".stripIndent()
        activities.sendNotification(notificationDestination, clusterName, action, message)
        activities.enableAsg(userContext, asgDeploymentNames.previousAsgName)
        activities.disableAsg(userContext, asgDeploymentNames.nextAsgName)
    }
}
