package com.kaspersky.kaspresso.testcases.core

import com.kaspersky.kaspresso.testcases.models.InternalStepInfo
import com.kaspersky.kaspresso.testcases.models.InternalTestInfo
import com.kaspersky.kaspresso.testcases.models.StepInfo
import com.kaspersky.kaspresso.testcases.models.StepStatus


/**
 * [StepManager] produces step. To make correct numeration for sub steps(see example below) it builds step hierarchy.
 *
 *
 * step("A"){
 *   step("B")
 *   step("C"){
 *     step("D")
 *   }
 * }
 *
 * Steps will have numbers:
 *
 * A : 1
 * B : 1.1
 * C : 1.2
 * D : 1.2.1
 *
 * Step numbers calculation algorithm:
 *
 * 0) Preconditions:
 *  - [currentStepResult] is null,
 *  - [stepsCounter] is 0. Number of steps(including sub steps)
 *  - [stepResultList] is empty. This is container to first level steps (step without parent step)
 *
 * 1) While calling [produceStep]:
 *
 * 1.1) If [currentStepResult] is null:
 *  - [stepsCounter] increasing by 1
 *  - Create new step.
 *  - Put it on the [stepResultList]
 *  - Step number is position on [stepResultList] + 1
 *  - Now it is a [currentStepResult]
 *
 * 1.2) If we already has [currentStepResult]:
 * - [stepsCounter] increasing by 1
 *  - Create new step
 *  - Put it on the sub steps of [currentStepResult]
 *  - Step number is: "${number of its parent}.${position at its parent sub steps + 1}"
 *  - Now it is a [currentStepResult]
 *
 * 2) While calling [onStepFinished]
 *  - If we trying to finish step that not a [currentStepResult] method throws [IllegalStateException] cause it is not a valid situation
 *  - mark step as [StepStatus.SUCCESS] or [StepStatus.FAILED]. It depends on 'error' arguments value
 *
 * 2.1) If step has parent
 *  - Now its parent is a [currentStepResult]
 * 2.2 If step has no parent
 *  - [currentStepResult] is null
 *
 *
 *
 */

class StepManager(private val testResult: InternalTestInfo) : StepProducer {
    private val stepResultList: MutableList<InternalStepInfo> = mutableListOf()

    private var currentStepResult: InternalStepInfo? = null

    private var stepsCounter: Int = 0

    override fun produceStep(description: String): StepInfo {
        val localCurrentStep = currentStepResult
        val step: InternalStepInfo
        if (localCurrentStep == null) {
            val stepNumber = mutableListOf(stepResultList.size + 1)
            step = produceStepInternal(description, stepNumber)
            currentStepResult = step
            stepResultList.add(step)
        } else {
            val stepNumber: MutableList<Int> = mutableListOf()
            stepNumber.addAll(localCurrentStep.stepNumber)
            stepNumber.add(localCurrentStep.internalSubSteps.size + 1)
            step = produceStepInternal(description, stepNumber, localCurrentStep)
            localCurrentStep.internalSubSteps.add(step)
            currentStepResult = step
        }
        return step
    }

    override fun onStepFinished(stepInfo: StepInfo, error: Throwable?) {
        val localCurrentStepResult = currentStepResult
        if (localCurrentStepResult != stepInfo)
            throw IllegalStateException(
                "Unable to finish step $stepInfo cause it is not current. " +
                        "Current step is $localCurrentStepResult. All steps: $stepResultList"
            )

        localCurrentStepResult.internalStatus = if (error == null) StepStatus.SUCCESS else StepStatus.FAILED
        localCurrentStepResult.internalThrowable = error
        currentStepResult = localCurrentStepResult.parentStep
    }

    private fun produceStepInternal(
        description: String,
        stepNumber: MutableList<Int>,
        parentStep: InternalStepInfo? = null
    ): InternalStepInfo {
        return InternalStepInfo(
            description = description,
            testClassName = testResult.testName,
            level = stepNumber.size,
            number = stepNumber.joinToString(separator = "."),
            ordinal = ++stepsCounter,
            stepNumber = stepNumber,
            parentStep = parentStep
        )
    }

    /**
     * Calling after many test finishing. It helps correctly finish all steps to return lately an actual steps hierarchy
     */
    fun onAllStepsFinished() {

        var localCurrentStep = currentStepResult
        var error: Throwable? = null

        while (localCurrentStep != null) {

            localCurrentStep.internalStatus = StepStatus.FAILED

            if (error == null) {
                val childFailedStepResult =
                    localCurrentStep.internalSubSteps.reversed().firstOrNull { it.internalStatus == StepStatus.FAILED }

                error = when (childFailedStepResult) {
                    null -> IllegalStateException("Unable to find error to finish failed step $localCurrentStep. Check all steps $stepResultList")
                    else -> childFailedStepResult.throwable
                }
            }

            localCurrentStep.internalThrowable = error
            localCurrentStep = localCurrentStep.parentStep
        }
        currentStepResult = null

        if (testResult.internalSteps.isNotEmpty()) {
            throw AssertionError("onAllStepsFinished called on already finished test")
        }
        testResult.internalSteps.addAll(stepResultList)

    }

    /**
     * Puts final throwable to test. We may get this error after all step finish in some of interceptors.
     */
    fun onTestFinished(throwable: Throwable? = null) {
        testResult.internalThrowable = throwable
    }
}
