;; TODO: Get the access key and secret from an environment variable.

{"fs.s3n.awsAccessKeyId"     (System/getenv "AWS_KEY")
 "fs.s3n.awsSecretAccessKey" (System/getenv "AWS_SECRET")
 "io.serializations" "backtype.hadoop.ThriftSerialization"}