package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest

/**
 * Information about an SQS queue.
 *
 * @property queueUrl The URL of the SQS queue
 * @property queueArn The ARN of the SQS queue
 */
data class QueueInfo(
    val queueUrl: String,
    val queueArn: String,
)

/**
 * Service interface for SQS queue operations.
 *
 * Used primarily for creating and managing the log ingestion queue
 * that receives S3 event notifications for EMR logs.
 */
interface SQSService {
    /**
     * Creates an SQS queue for log ingestion from S3.
     *
     * The queue is configured with a policy that allows S3 to send
     * messages when objects are created in the specified bucket.
     *
     * @param clusterId The cluster identifier used for naming the queue
     * @param bucketArn The ARN of the S3 bucket that will send notifications
     * @return Result containing QueueInfo on success, or an exception on failure
     */
    fun createLogIngestQueue(
        clusterId: String,
        bucketArn: String,
    ): Result<QueueInfo>

    /**
     * Deletes an SQS queue.
     *
     * @param queueUrl The URL of the queue to delete
     * @return Result indicating success or containing an exception on failure
     */
    fun deleteLogIngestQueue(queueUrl: String): Result<Unit>
}

/**
 * AWS SQS implementation of the SQSService interface.
 *
 * Provides SQS queue management with:
 * - Automatic retry logic using RetryUtil
 * - S3 notification policy configuration
 * - User feedback via OutputHandler
 *
 * @property sqsClient AWS SDK SQS client for queue operations
 * @property outputHandler Handler for user-facing output messages
 */
class AWSSQSService(
    private val sqsClient: SqsClient,
    private val outputHandler: OutputHandler,
) : SQSService {
    private val log = KotlinLogging.logger {}

    override fun createLogIngestQueue(
        clusterId: String,
        bucketArn: String,
    ): Result<QueueInfo> =
        runCatching {
            val queueName = "easy-db-lab-logs-$clusterId"

            outputHandler.handleMessage("Creating SQS queue: $queueName")

            val retryConfig = RetryUtil.createAwsRetryConfig<String>()
            val retry = Retry.of("sqs-create-queue", retryConfig)

            // Create the queue
            val queueUrl =
                Retry
                    .decorateSupplier(retry) {
                        val createRequest =
                            CreateQueueRequest
                                .builder()
                                .queueName(queueName)
                                .build()
                        sqsClient.createQueue(createRequest).queueUrl()
                    }.get()

            log.info { "Created SQS queue: $queueUrl" }

            // Get the queue ARN
            val getAttrsRequest =
                GetQueueAttributesRequest
                    .builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()

            val queueArn =
                sqsClient
                    .getQueueAttributes(getAttrsRequest)
                    .attributes()[QueueAttributeName.QUEUE_ARN]
                    ?: error("Failed to get queue ARN")

            // Configure the queue policy to allow S3 to send messages
            val policy = buildS3NotificationPolicy(queueArn, bucketArn)

            val setAttrsRequest =
                SetQueueAttributesRequest
                    .builder()
                    .queueUrl(queueUrl)
                    .attributes(mapOf(QueueAttributeName.POLICY to policy))
                    .build()

            sqsClient.setQueueAttributes(setAttrsRequest)
            log.info { "Configured SQS queue policy for S3 notifications" }

            outputHandler.handleMessage("SQS queue created: $queueUrl")

            QueueInfo(queueUrl, queueArn)
        }

    override fun deleteLogIngestQueue(queueUrl: String): Result<Unit> =
        runCatching {
            outputHandler.handleMessage("Deleting SQS queue: $queueUrl")

            val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
            val retry = Retry.of("sqs-delete-queue", retryConfig)

            Retry
                .decorateRunnable(retry) {
                    val deleteRequest =
                        DeleteQueueRequest
                            .builder()
                            .queueUrl(queueUrl)
                            .build()
                    sqsClient.deleteQueue(deleteRequest)
                    log.info { "Deleted SQS queue: $queueUrl" }
                }.run()

            outputHandler.handleMessage("SQS queue deleted")
        }

    /**
     * Builds an IAM policy document that allows S3 to send messages to the queue.
     *
     * @param queueArn The ARN of the SQS queue
     * @param bucketArn The ARN of the S3 bucket
     * @return JSON policy document string
     */
    private fun buildS3NotificationPolicy(
        queueArn: String,
        bucketArn: String,
    ): String =
        """
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Principal": {
                        "Service": "s3.amazonaws.com"
                    },
                    "Action": "sqs:SendMessage",
                    "Resource": "$queueArn",
                    "Condition": {
                        "ArnLike": {
                            "aws:SourceArn": "$bucketArn"
                        }
                    }
                }
            ]
        }
        """.trimIndent()
}
