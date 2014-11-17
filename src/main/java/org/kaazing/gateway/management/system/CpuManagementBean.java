/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.kaazing.gateway.management.system;


/**
 * Interface for data for a single NIC. As the individual beans do not
 * support things like summary intervals and change notifications (those
 * are done at the NIC List level), this is NOT an extension of ManagementBean,
 * even though it is defining several of the same methods.
 */
public interface CpuManagementBean {

    public static String[] SUMMARY_DATA_FIELD_LIST = 
            new String[] {"combined", "idle", "irq", "nice", "softIrq", "stolen", "sys", "user", "wait"};
    public static int SUMMARY_DATA_COMBINED_INDEX = 0;
    public static int SUMMARY_DATA_IDLE_INDEX = 1;
    public static int SUMMARY_DATA_IRQ_INDEX = 2;
    public static int SUMMARY_DATA_NICE_INDEX = 3;
    public static int SUMMARY_DATA_SOFTIRQ_INDEX = 4;
    public static int SUMMARY_DATA_STOLEN_INDEX = 5;
    public static int SUMMARY_DATA_SYS_INDEX = 6;
    public static int SUMMARY_DATA_USER_INDEX = 7;
    public static int SUMMARY_DATA_WAIT_INDEX = 8;    
    
    public int getId();
    
    /**
     * Return the combined CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getCombined();
    
    /**
     * Return the idle CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getIdle();

    /**
     * Return the IRQ CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getIrq();

    /**
     * Return the 'nice' CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getNice();

    /**
     * Return the soft IRQ CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getSoftIrq();

    /**
     * Return the ' CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getStolen();

    /**
     * Return the combined CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getSys();

    /**
     * Return the combined CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getUser();

    /**
     * Return the combined CPU percentage, as a value 0-100 (so 5% would be 5, not 0.05).
     */
    public double getWait();

    /**
     * Retrieve the summary data as a JSON string (used by JMX and the individual
     * SNMP entry row).
     */
    public String getSummaryData();
        
    public Number[] getSummaryDataValues();
    
    public void update(Double[] cpuPercentages);
}
