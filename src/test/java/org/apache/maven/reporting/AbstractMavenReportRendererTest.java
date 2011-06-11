package org.apache.maven.reporting;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Iterator;
import java.util.List;

import junit.framework.Assert;
import junit.framework.TestCase;
import junitx.util.PrivateAccessor;

/**
 * Test case for some public method in AbstractMavenReportRenderer.
 */
public class AbstractMavenReportRendererTest
    extends TestCase
{
    private static List<String> applyPattern( String pattern )
        throws Throwable
    {
        return (List<String>) PrivateAccessor.invoke( AbstractMavenReportRenderer.class, "applyPattern",
                                              new Class[] { String.class }, new Object[] { pattern } );
    }

    private static void checkPattern( String pattern, String[] expectedResult ) throws Throwable
    {
        List<String> result = applyPattern( pattern );
        Assert.assertEquals( "result size", expectedResult.length, result.size() );
        int i = 0;
        for ( Iterator<String> it = result.iterator(); it.hasNext(); )
        {
            String name = it.next();
            String href = it.next();
            Assert.assertEquals( expectedResult[i], name );
            Assert.assertEquals( expectedResult[i + 1], href );
            i += 2;
        }
    }

    private static void checkPatternIllegalArgument( String cause, String pattern ) throws Throwable
    {
        try
        {
            applyPattern( pattern );
            Assert.fail( cause + " should throw an IllegalArgumentException" );
        }
        catch ( IllegalArgumentException iae )
        {
            // ok
        }
    }

    /**
     * @throws Throwable if any
     */
    public void testApplyPattern() throws Throwable
    {
        // the most simple test
        checkPattern( "test {text,url}", new String[] { "test ", null, "text", "url" } );

        // check that link content is trimmed, and no problem if 2 text values are the same
        checkPattern( "test{ text , url }test", new String[] { "test", null, "text", "url", "test", null } );

        // check brace stacking
        checkPattern( "test{ {text} , url }", new String[] { "test", null, "{text}", "url" } );

        // check quoting
        checkPatternIllegalArgument( "unmatched brace", "{" );
        checkPattern( "'{'", new String[] { "'{'", null } );
        checkPattern( " ' { '.", new String[] { " ' { '.", null } );

        // unmatched quote: the actual behavior is to ignore that they are unmatched
        checkPattern( " '", new String[] { " '", null } );
        // but shouldn't it be different and throw an IllegalArgumentException?
        //    checkPatternIllegalArgument( "unmatched quote", " ' " );
        //    checkPatternIllegalArgument( "unmatched quote", " '" );
        // impact is too important to make the change for the moment

        // check double quoting
        checkPattern( " ''", new String[] { " '", null } );
        checkPattern( " '' ", new String[] { " '", null } );
        checkPattern( " ''   ", new String[] { " '", null } );

        // real world cases with quote
        checkPattern( "project''s info", new String[] { "project'", null, "s info", null } );
        checkPattern( "it''s a question of {chance, http://en.wikipedia.org/wiki/Chance}",
                      new String[] { "it'", null, "s a question of ", null,
                                     "chance", "http://en.wikipedia.org/wiki/Chance" } );
        checkPattern( "{s'inscrire,mail@mail.com}", new String[] { "s'inscrire", "mail@mail.com" } );

        // throwing an IllegalArgumentException in case of unmatched quote would avoid the following:
        checkPattern( "it's a question of {chance, http://en.wikipedia.org/wiki/Chance}",
                      new String[] { "it's a question of {chance, http://en.wikipedia.org/wiki/Chance}", null } );

        checkPattern( "{}test,", new String[] { "", null, "test,", null } );
        checkPattern( "Hi ${name}. How is it going, sir?", new String[] { "Hi ${name}. How is it going, sir?", null } );
    }
}
