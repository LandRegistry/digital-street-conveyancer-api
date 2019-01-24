package com.hmlr.api.listener

import com.hmlr.api.rpcClient.CORDA_VARS
import com.hmlr.states.InstructConveyancerState
import khttp.get
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.ContractState
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.loggerFor
import org.slf4j.Logger
import khttp.put
import net.corda.core.messaging.CordaRPCOps
import org.json.JSONException
import org.json.JSONObject

class EventListenerRPC {

    private val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json")

    companion object {
        val logger: Logger = loggerFor<EventListenerRPC>()
    }

    private fun processState(state: ContractState, proxy: CordaRPCOps) {
        when (state) {
            is InstructConveyancerState -> {
                logger.info("Got an InstructConveyancerState!")
                val caseResponse = get(
                    "${System.getenv("CASE_MANAGEMENT_API_URL")}/cases/${state.caseReferenceNumber}",
                    timeout = 15.0,
                    headers = headers
                )

                if (caseResponse.statusCode == 200) {
                    // Self Corda node party
                    val myNode = proxy.nodeInfo().legalIdentities.first()

                    // Check if we are the conveyancer that was instructed. This shouldn't be necessary.
                    if (state.conveyancer != myNode) {
                        logger.info("For case ${state.caseReferenceNumber}, we are not the conveyancer who was instructed! " +
                                "Mismatch between us (${myNode.name}) and the requested conveyancer (${state.conveyancer.name}).")
                        return
                    }

                    // Check if there's a client mismatch
                    val instructingSeller = state.user.userID.toInt()
                    val caseClient = caseResponse.jsonObject.getInt("client_id")
                    if (instructingSeller != caseClient) {
                        logger.info("Instructing seller ($instructingSeller) does not match the client ($caseClient) of case ${state.caseReferenceNumber}!")
                        return
                    }

                    val caseDTO = JSONObject()
                    try {
                        // Copy non changing data
                        caseDTO.put("case_reference", caseResponse.jsonObject["case_reference"])
                        caseDTO.put("case_type", caseResponse.jsonObject["case_type"])
                        caseDTO.put("status", caseResponse.jsonObject["status"])
                        caseDTO.put("assigned_staff_id", caseResponse.jsonObject["assigned_staff_id"])
                        caseDTO.put("client_id", caseResponse.jsonObject["client_id"])
                        caseDTO.put("counterparty_id", caseResponse.jsonObject["counterparty_id"])
                        caseDTO.put("counterparty_conveyancer_contact_id", caseResponse.jsonObject["counterparty_conveyancer_contact_id"])

                        val addressJson = JSONObject()
                        addressJson.put("house_name_number", caseResponse.jsonObject.getJSONObject("address")["house_name_number"])
                        addressJson.put("street", caseResponse.jsonObject.getJSONObject("address")["street"])
                        addressJson.put("town_city", caseResponse.jsonObject.getJSONObject("address")["town_city"])
                        addressJson.put("county", caseResponse.jsonObject.getJSONObject("address")["county"])
                        addressJson.put("country", caseResponse.jsonObject.getJSONObject("address")["country"])
                        addressJson.put("postcode", caseResponse.jsonObject.getJSONObject("address")["postcode"])
                        caseDTO.put("address", addressJson)

                        val counterpartyConveyancerOrgJson = JSONObject()
                        counterpartyConveyancerOrgJson.put("organisation", caseResponse.jsonObject.getJSONObject("counterparty_conveyancer_org")["organisation"])
                        counterpartyConveyancerOrgJson.put("locality", caseResponse.jsonObject.getJSONObject("counterparty_conveyancer_org")["locality"])
                        counterpartyConveyancerOrgJson.put("country", caseResponse.jsonObject.getJSONObject("counterparty_conveyancer_org")["country"])
                        val counterpartyConveyancerOrgState = caseResponse.jsonObject.getJSONObject("counterparty_conveyancer_org")["state"]
                        if (counterpartyConveyancerOrgState != JSONObject.NULL) counterpartyConveyancerOrgJson.put("state", counterpartyConveyancerOrgState)
                        val counterpartyConveyancerOrgOrganisationalUnit = caseResponse.jsonObject.getJSONObject("counterparty_conveyancer_org")["organisational_unit"]
                        if (counterpartyConveyancerOrgOrganisationalUnit != JSONObject.NULL) counterpartyConveyancerOrgJson.put("organisational_unit", counterpartyConveyancerOrgOrganisationalUnit)
                        val counterpartyConveyancerOrgCommonName = caseResponse.jsonObject.getJSONObject("counterparty_conveyancer_org")["common_name"]
                        if (counterpartyConveyancerOrgCommonName != JSONObject.NULL) counterpartyConveyancerOrgJson.put("common_name", counterpartyConveyancerOrgCommonName)
                        caseDTO.put("counterparty_conveyancer_org", counterpartyConveyancerOrgJson)

                        // Add the title number
                        caseDTO.put("title_number", state.titleID)
                    } catch (e: JSONException) {
                        logger.info("${state.titleID} failed to be assigned to case ${state.caseReferenceNumber} due to a JSON error!" +
                                "\n${e.message}")
                    }

                    // Updated case management api
                    val caseUpdateRequest = put(
                        "${System.getenv("CASE_MANAGEMENT_API_URL")}/cases/${state.caseReferenceNumber}",
                        timeout = 15.0,
                        data = caseDTO.toString(),
                        headers = headers
                    )
                    if (caseUpdateRequest.statusCode == 200) {
                        logger.info("${state.titleID} assigned to case ${state.caseReferenceNumber}")
                    } else {
                        logger.info("${state.titleID} failed to be assigned to case ${state.caseReferenceNumber}!" +
                                "\n${caseUpdateRequest.text}")
                    }
                } else {
                    logger.info("Case ${state.caseReferenceNumber} failed to be retrieved!\n${caseResponse.text}")
                }
            }
            else -> logger.info("Got an unknown state: ${state.javaClass.name}")
        }
    }

    fun run() {
        val nodeIpAndPort = "${System.getenv(CORDA_VARS.CORDA_NODE_HOST)}:${System.getenv(CORDA_VARS.CORDA_NODE_RPC_PORT)}"
        val nodeAddress = NetworkHostAndPort.parse(nodeIpAndPort)

        val client = CordaRPCClient(nodeAddress)

        val nodeUsername = System.getenv(CORDA_VARS.CORDA_USER_NAME)
        val nodePassword = System.getenv(CORDA_VARS.CORDA_USER_PASSWORD)
        val proxy = client.start(nodeUsername, nodePassword).proxy

        val (snapshot, updates) = proxy.vaultTrack(InstructConveyancerState::class.java)

        logger.info("Hopefully we should be tracking InstructConveyancerState")

        //snapshot.states.forEach {}
        updates.toBlocking().subscribe { update ->
            if (update.produced.isEmpty()) {
                logger.warn("Update is empty!")
            } else logger.info("We have an update!")

            update.produced.forEach { processState(it.state.data, proxy) }
        }
    }
}
