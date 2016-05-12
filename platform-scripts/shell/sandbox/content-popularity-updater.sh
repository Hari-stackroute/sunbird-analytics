#!/usr/bin/env bash

export SPARK_HOME=/home/ec2-user/spark-1.5.2-bin-hadoop2.3

## Job to run daily
cd /mnt/data/analytics/scripts
endDate=$(date "+%Y-%m-%d")

cp_config='{"search":{"type":"s3","queries":[{"bucket":"sandbox-data-store","prefix":"cus/","endDate":"'$endDate'","delta":0}]},"model":"org.ekstep.analytics.updater.UpdateContentPopularity","output":[{"to":"console","params":{"printEvent": false}}],"parallelization":8,"appName":"Content Popularity Updater","deviceMapping":false}'

nohup $SPARK_HOME/bin/spark-submit --master local[*] --jars /mnt/data/analytics/models/analytics-framework-0.5.jar --class org.ekstep.analytics.job.ContentPopularityUpdater /mnt/data/analytics/models/batch-models-1.0.jar --config "$cp_config" > "logs/$endDate-cp-updater.log" 2>&1&