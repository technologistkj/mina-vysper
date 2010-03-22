/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.vysper.xml.decoder;

/**
 * some valid XML is unsupported in XMPP. see RFC3920/11.
 * this exception signals XML, which might be valid, but is unsupported by our parser
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class UnsupportedXMLException extends DecodingException {
    public UnsupportedXMLException() {
        super();
    }

    public UnsupportedXMLException(String s) {
        super(s);
    }

    public UnsupportedXMLException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public UnsupportedXMLException(Throwable throwable) {
        super(throwable);
    }
}