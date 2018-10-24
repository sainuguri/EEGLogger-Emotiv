/* 
* Created by Sai Nuguri
* Program reads EEG data from Emotiv and writes to AWS DynamoDB
*
*/

package com.emotiv.examples.EEGLogger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import com.emotiv.Iedk.Edk;
import com.emotiv.Iedk.EdkErrorCode;
import com.emotiv.Iedk.IEegData;
import com.emotiv.Iedk.PerformanceMetrics;
import org.example.basicApp.model.VrMeasurement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.example.basicApp.client.MeasurementProcessor;
import org.example.basicApp.ddb.DynamoDBWriter;
import org.example.basicApp.model.DdbRecordToWrite;
import org.example.basicApp.model.SingleMeasurementValue;
import org.example.basicApp.model.VrMeasurement;
import org.example.basicApp.utils.DynamoDBUtils;
import org.example.basicApp.utils.SampleUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClient;

//import com.playfab.PlayFabClientAPI;
//import com.playfab.PlayFabClientModels;
//import com.playfab.PlayFabClientModels.LoginResult;
//import com.playfab.PlayFabErrors.*;
//import com.playfab.PlayFabSettings;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

//import java.util.HashMap;
//import java.util.Map; 

public class EEGLogger {

    private static final Log LOG = LogFactory.getLog(EEGLogger.class);
   	private static boolean _running;
	private static final float deviation = 0.1f;
    static AmazonDynamoDB dynamoDB;
    private static final int numUsers=6;
    private static DdbRecordToWrite ddbRecordToWrite;
    
    
    public static void main(String[] args) throws FileNotFoundException {
    	
        if(args.length != 2) {
        System.err.println("Usage: " + DynamoDBWriter.class.getSimpleName()
                + "<DynamoDB table name> <region>");
        System.exit(1);
        }

        
	    String dynamoTableName = args[0];
	    Region region = SampleUtils.parseRegion(args[1]);
	    
        AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
        ClientConfiguration clientConfig = SampleUtils.configureUserAgentForSample(new ClientConfiguration());
        AmazonKinesis kinesis = new AmazonKinesisClient(credentialsProvider, clientConfig);
        kinesis.setRegion(region);
        AmazonDynamoDB dynamoDB = new AmazonDynamoDBClient(credentialsProvider, clientConfig);
        dynamoDB.setRegion(region);
         
        DynamoDBUtils dynamoDBUtils = new DynamoDBUtils(dynamoDB);
        dynamoDBUtils.createDynamoTableIfNotExists(dynamoTableName);
        LOG.info(String.format("%s DynamoDB table is ready for use", dynamoTableName));
	
        // Describe our new table
        DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(dynamoTableName);
        TableDescription tableDescription = dynamoDB.describeTable(describeTableRequest).getTable();
        System.out.println("Table Description: " + tableDescription);
        
        
        Pointer eEvent   = Edk.INSTANCE.IEE_EmoEngineEventCreate();
        Pointer eState   = Edk.INSTANCE.IEE_EmoStateCreate();
        float Excitement = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetExcitementLongTermScore(eState);
        float Relaxation = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetRelaxationScore(eState);
        float Stress     = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetStressScore(eState);
        float Engagement = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetEngagementBoredomScore(eState);
        float Interest   = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetInterestScore(eState);
        float Focus      = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetFocusScore(eState);

        IntByReference userID = null;
        IntByReference nSamplesTaken = null;
        short composerPort = 3008;
        int option = 1;
        int state  = 0;
        float secs = 1;
        boolean readytocollect = false;

        userID = new IntByReference(0);
        nSamplesTaken = new IntByReference(0);

        switch (option) {

	        case 1: {			   	   
	            if (Edk.INSTANCE.IEE_EngineConnect("Emotiv Systems-5") != EdkErrorCode.EDK_OK.ToInt()) {
	            	
                    System.out.println("Emotiv Engine start up failed.");
                    return;
	            }
	            break;
	        }

	        case 2: {

	            System.out.println("Target IP of EmoComposer: [127.0.0.1] ");

	            if (Edk.INSTANCE.IEE_EngineRemoteConnect("127.0.0.1", composerPort,
	                "Emotiv Systems-5") != EdkErrorCode.EDK_OK.ToInt()) {

                    System.out.println("Cannot connect to EmoComposer on [127.0.0.1]");
                    return;
	            }

	            System.out.println("Connected to EmoComposer on [127.0.0.1]");
	            break;
	        }

	        default:

	            System.out.println("Invalid option...");
	            return;
        }

        Pointer hData = IEegData.INSTANCE.IEE_DataCreate();
        IEegData.INSTANCE.IEE_DataSetBufferSizeInSec(secs);
        System.out.print("Buffer size in secs: ");
        System.out.println(secs);
        System.out.println("Start receiving EEG Data!");       

        try {

		    // create file
		   	PrintWriter fout;
            fout = new PrintWriter("EEGLogger.csv");

            while (true) {
	            //state = Edk.INSTANCE.IEE_EngineGetNextEvent(eEvent);
			   	try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	            // New event needs to be handled

			   	System.out.print("Current state is: " + EdkErrorCode.EDK_OK.ToInt() + "\n");
			   	
			   	System.out.print("state is: "  + state);
			   	
			   	
	            if (state == EdkErrorCode.EDK_OK.ToInt()) {

	                int eventType = Edk.INSTANCE.IEE_EmoEngineEventGetType(eEvent);

	                Edk.INSTANCE.IEE_EmoEngineEventGetUserId(eEvent, userID);

//	                if (eventType == Edk.IEE_Event_t.IEE_UserAdded.ToInt())

	                    if (userID != null) {

	                        System.out.println("User added");

	                        IEegData.INSTANCE.IEE_DataAcquisitionEnable(userID.getValue(), true);

	                        readytocollect = true;

	                    }

	            } else if (state != EdkErrorCode.EDK_NO_EVENT.ToInt()) {

	                System.out.println("Internal error in Emotiv Engine!");

	                break;

	            }



	            if (readytocollect) {

	                IEegData.INSTANCE.IEE_DataUpdateHandle(userID.getValue(), hData);
	                IEegData.INSTANCE.IEE_DataGetNumberOfSample(hData, nSamplesTaken);

	                if (nSamplesTaken != null) {
//
//	                    if (nSamplesTaken.getValue() != 0) {
//
//	                        System.out.print("Updated: ");
//	                        System.out.println(nSamplesTaken.getValue());
//	                        double[] data = new double[nSamplesTaken.getValue()];
//	                        for (int sampleIdx = 0; sampleIdx < nSamplesTaken.getValue(); ++sampleIdx) {
//	                            for (int i = 0; i < 20; i++) {
//	                                IEegData.INSTANCE.IEE_DataGet(hData, i, data, nSamplesTaken.getValue());
//	                                fout.printf("%f",data[sampleIdx]);
//	                                fout.printf(",");
//	                            }
//	                            fout.printf("\n");
//	                        }
//	                    }
	                }     

	                Engagement = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetEngagementBoredomScore(eEvent);
	                Excitement = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetExcitementLongTermScore(eEvent);
	                Relaxation = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetRelaxationScore(eEvent);
	                Stress = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetStressScore(eEvent);         
	                Focus = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetFocusScore(eEvent);
	                Interest = PerformanceMetrics.INSTANCE.IS_PerformanceMetricGetInterestScore(eEvent);               

//	                Engagement = (float) 0.7;
//	                Excitement = (float) 0.654;
//	                Relaxation = (float) 0.444;
//	                Stress = (float)0.683;
//	                Focus = (float)0.778;
//	                Interest = (float)0.889;
	                
	                // generate random data and try to persist data into local file first
		            VrMeasurement measurementRecord =  new VrMeasurement();	            

		            for (int i=1; i<numUsers+1; i++) {
		            	ddbRecordToWrite = generateDBRecord(measurementRecord, "user"+i);
		                System.out.printf("record ready to write for user %s is: %s \n" ,i, ddbRecordToWrite.toString());

			            Map<String, AttributeValue> item = newItem(ddbRecordToWrite);			            
                        fout.printf("%s,",item.get("resource"));  
                        
                        fout.printf("%s,",item.get("timestamp"));                    
                        fout.printf("%s,",item.get("host"));                    
                        fout.printf("%s,",item.get("values"));                    
                        fout.printf("\n");
			            	
			            	
			            PutItemRequest putItemRequest = new PutItemRequest(dynamoTableName, item);
			            PutItemResult putItemResult = dynamoDB.putItem(putItemRequest);
			            System.out.println("Result: " + putItemResult);	 
			            	    			            
		            }
	                
	            }
	        }

	        fout.flush();
	        fout.close();

        } catch (IOException e) {

            System.out.print("Exception");

        }

	    finally{

        //            fout.close();               

	    }

	    Edk.INSTANCE.IEE_EngineDisconnect();

	    Edk.INSTANCE.IEE_EmoStateFree(eState);

	    Edk.INSTANCE.IEE_EmoEngineEventFree(eEvent);

	    System.out.println("Disconnected!");

    }

    private static DdbRecordToWrite generateDBRecord(VrMeasurement measurementRecord, String user) {
    	
    	DdbRecordToWrite ddbRecordToWrite = new DdbRecordToWrite();
        ddbRecordToWrite.setResource(measurementRecord.getResource());
        //ddbRecordToWrite.setTimeStamp(measurementRecord.getTimeStamp());
        ddbRecordToWrite.setHost(user);
        
        Date date = new Date();
        date.setTime(System.currentTimeMillis());
        ddbRecordToWrite.setTimeStamp(toISO8601UTC(date));
        
    	List<SingleMeasurementValue> measurementValues = new ArrayList<SingleMeasurementValue>();
       	SingleMeasurementValue value1 = new SingleMeasurementValue("{\"measurement\":\"engagement\",\"value\":", getRandomFloat(0.9f),"}");
    	SingleMeasurementValue value2 = new SingleMeasurementValue("{\"measurement\":\"focus\",\"value\":", getRandomFloat(0.8f),"}");
    	SingleMeasurementValue value3 = new SingleMeasurementValue("{\"measurement\":\"excitement\",\"value\":", getRandomFloat(0.7f),"}");
    	SingleMeasurementValue value4 = new SingleMeasurementValue("{\"measurement\":\"frustration\",\"value\":", getRandomFloat(0.2f),"}");
    	SingleMeasurementValue value5 = new SingleMeasurementValue("{\"measurement\":\"stress\",\"value\":", getRandomFloat(0.1f),"}");
    	SingleMeasurementValue value6 = new SingleMeasurementValue("{\"measurement\":\"relaxation\",\"value\":", getRandomFloat(0.5f),"}");
    	measurementValues.add(value1);
    	measurementValues.add(value2);
    	measurementValues.add(value3);
    	measurementValues.add(value4);
    	measurementValues.add(value5);
    	measurementValues.add(value6);
    	
        ddbRecordToWrite.setValues(measurementValues);		            	            
        

    	return ddbRecordToWrite;
    }



    private static Map<String, AttributeValue> newItem(DdbRecordToWrite record) {
    	
    	Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put("resource", new AttributeValue(record.getResource()));
        item.put("timestamp", new AttributeValue(record.getTimeStamp()));
        item.put("host", new AttributeValue(record.getHost()));
        item.put("values", new AttributeValue(record.getValues().toString()));


        return item;
    }

    public static String toISO8601UTC(Date date) {
    	  TimeZone tz = TimeZone.getTimeZone("UTC");
    	  DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    	  df.setTimeZone(tz);
    	  return df.format(date);
    	}
        
    public static Float getRandomFloat(Float mean) {
    	
    	Random rand = new Random();	
    	// set the price using the deviation and mean price
        
    	Float max = mean + deviation;
    	Float min = mean - deviation;

        // randomly pick a quantity of shares
        Float value = rand.nextFloat() * (max - min) + min; 

        return value;
    }
    
}

//private static void OnLoginComplete(FutureTask<PlayFabResult<LoginResult>> loginTask) {

// TODO Auto-generated method stub
                               

//            }

//}





