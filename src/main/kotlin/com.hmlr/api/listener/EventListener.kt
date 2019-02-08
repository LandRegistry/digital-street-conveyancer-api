package com.hmlr.api.listener

import com.hmlr.api.SMSDispatcher
import com.hmlr.api.rpcClient.CORDA_VARS
import com.hmlr.model.AgreementStatus
import com.hmlr.states.LandAgreementState
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.ContractState
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.loggerFor
import org.slf4j.Logger
import net.corda.core.messaging.CordaRPCOps

class EventListenerRPC {

    companion object {
        val logger: Logger = loggerFor<EventListenerRPC>()
    }

    private fun processState(state: ContractState, proxy: CordaRPCOps) {
        val myIdentity = proxy.nodeInfo().legalIdentities.first()

        when (state) {
            is LandAgreementState -> {
                logger.info("Got an LandAgreementState!")

                when (myIdentity) {
                    state.sellerConveyancer -> {
                        with (state.seller) {
                            when (state.status) {
                                AgreementStatus.APPROVED -> SMSDispatcher.sendSMSAgreementSignRequestSeller(phone, "$forename $surname", state.titleID)
                                AgreementStatus.TRANSFERRED -> SMSDispatcher.sendSMSTitleTransferred(phone, "$forename $surname", state.titleID)
                                else -> Unit
                            }
                        }
                    }
                    state.buyerConveyancer -> {
                        with (state.buyer) {
                            when (state.status) {
                                AgreementStatus.SIGNED -> SMSDispatcher.sendSMSAgreementSignRequestBuyer(phone, "$forename $surname", state.titleID)
                                AgreementStatus.TRANSFERRED -> SMSDispatcher.sendSMSTitleTransferred(phone, "$forename $surname", state.titleID)
                                else -> Unit
                            }
                        }
                    }
                    else -> logger.warn("I am neither the buyer's, nor the seller's, conveyancer")
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

        val (snapshot, updates) = proxy.vaultTrack(LandAgreementState::class.java)

        logger.info("Hopefully we should be tracking LandAgreementState")

        //snapshot.states.forEach {}
        updates.toBlocking().subscribe { update ->
            if (update.produced.isEmpty()) {
                logger.warn("Update is empty!")
            } else logger.info("We have an update!")

            update.produced.forEach { processState(it.state.data, proxy) }
        }
    }
}
