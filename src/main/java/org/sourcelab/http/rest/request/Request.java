/**
 * Copyright 2019 SourceLab.org https://github.com/SourceLabOrg/HttpClientWrapper
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.sourcelab.http.rest.request;

import java.io.IOException;

/**
 * Interface for all Requests to implement.
 * @param <T> return type of request.
 */
public interface Request<T> {

    /**
     * The name of the API end point to issue a request against.  This is appended to the API Hostname.
     * @return The name of the end point this request uses.
     */
    String getApiEndpoint();

    /**
     * Request Method, IE POST, GET, etc..
     * @return The type of HTTP Request.
     */
    RequestMethod getRequestMethod();

    /**
     * Object to be submitted as the body of the request.  It will be serialized to JSON using Jackson.
     * @return Object representing request body content.
     */
    Object getRequestBody();

    /**
     * Parse the rest service's response into a concrete object.
     * @param responseStr The servers response in string format.
     * @return A concrete object representing the result.
     * @throws IOException on parsing errors.
     */
    T parseResponse(final String responseStr) throws IOException;
}
