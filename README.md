# MBTA Vehicle Positions
This utility is for pulling the current positions of all vehicles on the Commuter Rail (CR) and the rapid transit (T) trains for Massachusetss Bay Transit Authority (MBTA). The MBTA provides access to this data with a public REST interface (https://api-v3.mbta.com/docs/swagger/index.html). To use this code you will need to have an MBTA API Key.

# License
Licensed under the Apache 2.0 agreement.

# Requirements
1. Access to an AWS Account where IAM User credentials and an optional IAM Role ARN are needed to provide write access to an S3 bucket that will store the resulting data.
2. Access to an MBTA API Key by registering at https://api-v3.mbta.com/register. This is needed to allow for the a maximum API access rate of 1000 requests per minute. If you do not wish to provide an API key, then you will be restricted to 10 requests per minute to the MBTA Rest API and the update period is increase to 10 minutes from 15 seconds. Therefore it is highly recommended you register for the MBTA Api Key.

# Configuration Options
There are three ways of providing all the configuration needed to run this utility.

```
mbta {
    api = null # Optional. Ideally you should replace with your MBTA Api Key. This will ensure the faster request rate.
    aws {
        credentials {
            accessKey              = null # Required. Replace with your AWS Access Key ID (IAM User credentials).
            secretKey              = null # Required. Replace with your AWS Secret Key (IAM User credentials).
            s3AccessRole           = null # Optional. This should be an IAM Role Arn that you IAM user can assume.
            s3AccessRoleExternalId = null # Optional. If the Role Arn requires an external Id in the trust relationship.
        }

        region = "us-east-1" # # Change to specify the AWS region you are running your application within.

        s3 {
            bucket = "cs-gmills-mbta"          # Required. Change to reflect your AWS S3 bucket.
            prefix = "MBTA/vehicle/positions"  # Required. Change to reflect your path style prefix.
        }
    }
}
```

### 1. Modify the `source/resources/MBTA.conf` resource file
Simply update the lines for IAM User Access, and an optional roleArn. You will also need to provide an MBTA Key and the name of a bucket the IAM User/RoleArn will have write access to.
Note that this format is `HOCON` although `JSON` or `YAML` is also a valid syntax to use instead.

### 2. Read in the Config via `MBTA_CONFIG` environment variable.
You can instead simply populate the environment variable with a string that is the `HOCON`. For example:
```
export MBTA_CONFIG="$(cat source/resources/MBTA.conf)"
```

### 3. Individial Environment variables
The back stop is to supply the config items via these environment variables instead:
* `AWS_ACCESS_KEY_ID`
* `AWS_SECRET_ACCESS_KEY`
* `AWS_REGION` if not supplied the default is `US_EAST_1`
* `MBTA_API_KEY` - optional.
* `MBTA_S3_ROLEARN` - optional IAM Role Arn
* `MBTA_S3_ROLEARN_EXTERNAL_ID` - optional external ID for the IAM RoleArn trust relationship. Recommended is using a RoleArn.
* `MBTA_STORAGE_BUCKET` - The bucket to where the position data will be placed.
* `MBTA_STORAGE_PREFIX` - a path style prefix tht will be appended to any Vechile Route Position data.

# Building and Running
To Build and then run the utility from your GIT clone workspace.

`./gradlew run`

This will both compile the Jar and then run it from within Gradle.

# Position Data
1. The utility will first pull all the MBTA Commuter Rail and Rapid Transit rail routes.
2. For each route the current running vehicles per route are then pulled.
3. For each route the stop data is pulled and then used to augment the position data with intransit or stoped at station information.

A typical position JSON object can look like:
```
{
  "bearing": 256,
  "currentStatus": "IN_TRANSIT_TO",
  "currentStopSequence": 50,
  "destination": "Wachusett",
  "direction": "Outbound",
  "directionId": 0,
  "latitude": 42.37403106689453,
  "longitude": -71.23724365234375,
  "position": "42.37403106689453, -71.23724365234375",
  "routeId": "CR-Fitchburg",
  "stopId": "FR-0115",
  "stopName": "Brandeis/Roberts",
  "stopPlatformName": "Commuter Rail",
  "stopZone": "CR-zone-2",
  "timeStamp": 1658686457009,
  "tripId": "CR-541129-2407",
  "updatedAt": "2022-07-24T14:13:00-04:00",
  "vehicleId": "1652"
}
{
  "bearing": 94,
  "currentStatus": "IN_TRANSIT_TO",
  "currentStopSequence": 10,
  "destination": "North Station",
  "direction": "Inbound",
  "directionId": 1,
  "latitude": 42.58123016357422,
  "longitude": -71.80029296875,
  "position": "42.58123016357422, -71.80029296875",
  "routeId": "CR-Fitchburg",
  "speed": 6.7,
  "stopId": "FR-0494-CS",
  "stopName": "Fitchburg",
  "stopPlatformName": "Track 3 (All Trains)",
  "stopZone": "CR-zone-8",
  "timeStamp": 1658686457009,
  "tripId": "CR-541071-2408",
  "updatedAt": "2022-07-24T14:14:00-04:00",
  "vehicleId": "1650"
}
```

If the bucket name is for example `cs-gmills-mbta` with a path prefix of `MBTA/vehicle/positions` then the path for say this `CR-Fitchburg` route vehicle position data will be:
`cs-gmills-mbta/MBTA/vehicle/positions/CR-Fitchburg/` and therefore using the AWS Cli command you could find the position data using:

```
aws s3 ls s3://cs-gmills-mbta/MBTA/vehicle/positions/CR-Fitchburg/
...
2022-07-24 14:12:19       1008 1658686338153.json
2022-07-24 14:12:34       1008 1658686353252.json
2022-07-24 14:12:49       1003 1658686368213.json
2022-07-24 14:13:04       1005 1658686383214.json
2022-07-24 14:13:19       1003 1658686398300.json
2022-07-24 14:13:34       1002 1658686413272.json
2022-07-24 14:13:49       1002 1658686428271.json
2022-07-24 14:14:04       1002 1658686443389.json
2022-07-24 14:14:19        995 1658686458410.json
...
```
Note that an object name used to store the vehicle position data is the current Epoch milliseconds elapsed and is therefore a useful timestamping of the written data and the temporal order of the data.
