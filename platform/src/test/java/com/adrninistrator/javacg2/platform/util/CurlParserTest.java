package com.adrninistrator.javacg2.platform.util;

import com.adrninistrator.javacg2.platform.dto.RequestChainDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cURL解析器测试
 */
class CurlParserTest {

    @Test
    void testSimpleGetRequest() {
        String curl = "curl 'http://localhost:5173/api/repos'";
        
        RequestChainDTO dto = CurlParser.parseCurl(curl);
        
        assertNotNull(dto);
        assertEquals(1, dto.getRequestChain().size());
        
        RequestChainDTO.RequestInfo request = dto.getRequestChain().get(0);
        assertEquals("GET", request.getMethod());
        assertEquals("/api/repos", request.getUrl());
        assertEquals("http://localhost:5173/api/repos", request.getFullUrl());
    }

    @Test
    void testPostRequestWithData() {
        String curl = "curl -X POST 'http://localhost:5173/api/repos' " +
                     "-H 'Content-Type: application/json' " +
                     "-d '{\"name\":\"test\",\"gitUrl\":\"https://github.com/test/test.git\"}'";
        
        RequestChainDTO dto = CurlParser.parseCurl(curl);
        
        assertNotNull(dto);
        assertEquals(1, dto.getRequestChain().size());
        
        RequestChainDTO.RequestInfo request = dto.getRequestChain().get(0);
        assertEquals("POST", request.getMethod());
        assertEquals("/api/repos", request.getUrl());
        assertNotNull(request.getRequestBody());
        assertTrue(request.getRequestBody().contains("test"));
    }

    @Test
    void testMultilineGetRequest() {
        String curl = "curl 'http://localhost:8080/api/user/123' \\\n" +
                     "  -H 'Accept: application/json' \\\n" +
                     "  -H 'Authorization: Bearer token123'";
        
        RequestChainDTO dto = CurlParser.parseCurl(curl);
        
        assertNotNull(dto);
        RequestChainDTO.RequestInfo request = dto.getRequestChain().get(0);
        assertEquals("GET", request.getMethod());
        assertEquals("/api/user/123", request.getUrl());
    }

    @Test
    void testComplexPostRequest() {
        String curl = "curl 'https://api.example.com/v1/orders' \\\n" +
                     "  -X POST \\\n" +
                     "  -H 'Content-Type: application/json' \\\n" +
                     "  -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9' \\\n" +
                     "  --data-raw '{\"productId\":123,\"quantity\":2,\"price\":99.99}'";
        
        RequestChainDTO dto = CurlParser.parseCurl(curl);
        
        assertNotNull(dto);
        RequestChainDTO.RequestInfo request = dto.getRequestChain().get(0);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/orders", request.getUrl());
        assertEquals("https://api.example.com/v1/orders", request.getFullUrl());
        assertNotNull(request.getRequestBody());
        assertTrue(request.getRequestBody().contains("productId"));
    }

    @Test
    void testIsCurlCommand() {
        assertTrue(CurlParser.isCurlCommand("curl http://example.com"));
        assertTrue(CurlParser.isCurlCommand("curl 'http://example.com'"));
        assertTrue(CurlParser.isCurlCommand("  curl http://example.com  "));
        
        assertFalse(CurlParser.isCurlCommand("http://example.com"));
        assertFalse(CurlParser.isCurlCommand("{\"url\":\"http://example.com\"}"));
        assertFalse(CurlParser.isCurlCommand(""));
        assertFalse(CurlParser.isCurlCommand(null));
    }

    @Test
    void testIsJson() {
        assertTrue(CurlParser.isJson("{\"key\":\"value\"}"));
        assertTrue(CurlParser.isJson("[{\"key\":\"value\"}]"));
        assertTrue(CurlParser.isJson("  {\"key\":\"value\"}  "));
        
        assertFalse(CurlParser.isJson("curl http://example.com"));
        assertFalse(CurlParser.isJson("not json"));
        assertFalse(CurlParser.isJson(""));
        assertFalse(CurlParser.isJson(null));
    }

    @Test
    void testRealWorldExample1() {
        // Chrome DevTools 复制的 cURL
        String curl = "curl 'http://localhost:5173/api/repos' \\\n" +
                     "  -H 'Accept: application/json, text/plain, */*' \\\n" +
                     "  -H 'Accept-Language: zh-CN,zh;q=0.9' \\\n" +
                     "  -H 'Connection: keep-alive' \\\n" +
                     "  -H 'Referer: http://localhost:5173/repos' \\\n" +
                     "  --compressed";
        
        RequestChainDTO dto = CurlParser.parseCurl(curl);
        
        assertNotNull(dto);
        assertEquals("GET", dto.getRequestChain().get(0).getMethod());
        assertEquals("/api/repos", dto.getRequestChain().get(0).getUrl());
    }

    @Test
    void testRealWorldExample2() {
        // Firefox 复制的 cURL
        String curl = "curl -X POST http://localhost:5173/api/debug/analyze-request-chain " +
                     "-H \"Content-Type: application/json\" " +
                     "-d '{\"sessionId\":123,\"userAction\":\"测试\",\"timestamp\":1234567890,\"url\":\"http://test.com\",\"requestChain\":[],\"dataFlow\":[]}'";
        
        RequestChainDTO dto = CurlParser.parseCurl(curl);
        
        assertNotNull(dto);
        assertEquals("POST", dto.getRequestChain().get(0).getMethod());
        assertTrue(dto.getRequestChain().get(0).getRequestBody().contains("sessionId"));
    }
}
