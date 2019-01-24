package com.hmlr.api


import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.junit4.SpringRunner
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.http.MediaType


@RunWith(SpringRunner::class)
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTests() {

    @LocalServerPort
    private val port: Int = 8080

    @Autowired
    private val restTemplate: TestRestTemplate? = null

    private val Url: String = "http://localhost:$port/api/"

    @Test
    fun `me`() {
        /*
        val obj = restTemplate!!.getForObject(Url+"/me", MediaType.APPLICATION_JSON_VALUE::class.java)
        print(obj.toString())
        */
    }

}
