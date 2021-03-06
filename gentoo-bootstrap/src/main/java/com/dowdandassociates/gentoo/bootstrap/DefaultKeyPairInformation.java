/*
 *
 * DefaultKeyPairInformation.java
 *
 *-----------------------------------------------------------------------------
 * Copyright 2013 Dowd and Associates
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
 *-----------------------------------------------------------------------------
 *
 */

package com.dowdandassociates.gentoo.bootstrap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeKeyPairsRequest;
import com.amazonaws.services.ec2.model.Filter;

import com.google.inject.Inject;

import com.netflix.governator.annotations.Configuration;
import com.netflix.governator.guice.lazy.FineGrainedLazySingleton;

import org.apache.commons.lang3.time.DateFormatUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FineGrainedLazySingleton
public class DefaultKeyPairInformation implements KeyPairInformation
{
    private static Logger log = LoggerFactory.getLogger(DefaultKeyPairInformation.class);

    private boolean builtKeyPair;
    private AmazonEC2 ec2Client;

    @Configuration("com.dowdandassociates.gentoo.bootstrap.KeyPair.filename")
    private String filename = null;

    @Configuration("com.dowdandassociates.gentoo.bootstrap.KeyPair.name")
    private String name = null;

    @Inject
    public DefaultKeyPairInformation(AmazonEC2 ec2Client)
    {
        this.ec2Client = ec2Client;
    }

    @PostConstruct
    private void setup()
    {
        boolean nameSet = (null != name);
        boolean filenameSet = (null != filename); 
        boolean keyExists = false;
        if (nameSet)
        {
            log.info("Checking if key pair \"" + name + "\" exists");
            keyExists = !(ec2Client.describeKeyPairs(new DescribeKeyPairsRequest().
                    withFilters(new Filter().withName("key-name").withValues(name))).getKeyPairs().isEmpty());
        }

        if (keyExists && !filenameSet)
        {
            log.warn("Key pair \"" + name + "\" exists, but private key location is not specified");
            keyExists = false;
        }

        if (!keyExists)
        {
            if (!nameSet)
            {
                name = "gentoo-bootstrap-" + DateFormatUtils.formatUTC(System.currentTimeMillis(), "yyyyMMdd'T'HHmmssSSS'Z'");
            }

            if (!filenameSet)
            {
                try
                {
                    filename = Files.createTempFile(
                            name,
                            ".pem",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toString();
                }
                catch (IOException ioe)
                {
                    log.warn("Cannot create temp file", ioe);
                    filename = name + ".pem";
                }
            }

            log.info("Creating key pair \"" + name + "\"");

            CreateKeyPairResult createResult = ec2Client.createKeyPair(new CreateKeyPairRequest().
                    withKeyName(name));
        
            try
            {
                log.info("Saving pem file to \"" + filename + "\"");

                BufferedWriter outfile = new BufferedWriter(new FileWriter(filename));

                try
                {
                    outfile.write(createResult.getKeyPair().getKeyMaterial());
                }
                catch (IOException ioe)
                {
                    String message = "Error writing to file \"" + filename + "\"";
                    log.error(message, ioe);
                    throw new RuntimeException(message, ioe);
                }
                finally
                {
                    outfile.close();
                }
            }
            catch (IOException ioe)
            {
                String message = "Error opening file \"" + filename + "\"";
                log.error(message, ioe);
                throw new RuntimeException(message, ioe);
            }

            builtKeyPair = true;

            log.info("Key pair \"" + name + "\" built");
        }
        else
        {
            builtKeyPair = false;
            log.info("Key pair \"" + name + "\" exists");
        }

        if (filename.startsWith("~" + File.separator))
        {
            filename = System.getProperty("user.home") + filename.substring(1);
        }
    }

    @PreDestroy
    private void tearDown()
    {
        if (builtKeyPair)
        {
            log.info("Deleting key pair \"" + name + "\"");

            ec2Client.deleteKeyPair(new DeleteKeyPairRequest().
                    withKeyName(name));

            try
            {
                File file = new File(filename);
                file.delete();
            }
            catch (NullPointerException | SecurityException e)
            {
                log.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String getFilename()
    {
        return filename;
    }
}

