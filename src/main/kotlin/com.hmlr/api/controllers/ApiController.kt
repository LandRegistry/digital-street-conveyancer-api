package com.hmlr.api.controllers

import com.hmlr.api.common.VaultQueryHelperConsumer
import com.hmlr.api.common.models.*
import com.hmlr.api.keyutils.KeyUtils
import com.hmlr.api.rpcClient.NodeRPCConnection
import com.hmlr.flows.*
import com.hmlr.model.*
import com.hmlr.states.*
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.IdentityService
import net.corda.core.utilities.loggerFor
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate


@Suppress("unused")
@RestController
@RequestMapping("/api")
class ApiController(@Suppress("CanBeParameter") private val rpc: NodeRPCConnection) : VaultQueryHelperConsumer() {

    override val rpcOps = rpc.proxy
    override val myIdentity = rpcOps.nodeInfo().legalIdentities.first()

    companion object {
        private val logger = loggerFor<ApiController>()
    }

    /**
     * Return the node's name
     */
    @GetMapping(value = "/me", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun me() = mapOf("me" to myIdentity.toDTOWithName())

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GetMapping(value = "/peers", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun peers() = mapOf("peers" to rpcOps.networkMapSnapshot()
            .asSequence()
            .filter { nodeInfo -> nodeInfo.legalIdentities.first() != myIdentity }
            .map { it.legalIdentities.first().toDTOWithName() }
            .toList())

    /**
     * Request a title to be issued to the ledger
     */
    @PostMapping(value = "/titles/{title-number}",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun requestTitle(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("POST /titles/$titleNumber")

        val requestIssuanceState = vaultQueryHelper {
            //Get details of title
            val conveyancerInstruction: StateAndInstant<InstructConveyancerState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return 404 if null
            conveyancerInstruction ?: return ResponseEntity.notFound().build()

            //Build state
            conveyancerInstruction.state.run {
                RequestIssuanceState(
                        titleID,
                        titleIssuer,
                        conveyancer,
                        user,
                        RequestIssuanceStatus.PENDING,
                        linearId.toString()
                )
            }
        }

        //start flow and return response
        return responseEntityFromFlowHandle {
            it.startFlowDynamic(
                    RequestIssuanceFlow::class.java,
                    requestIssuanceState,
                    requestIssuanceState.instructionStateLinearID
            )
        }
    }

    /**
     * Gets a title's sales agreement
     */
    @GetMapping(value = "/titles/{title-number}/sales-agreement",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getSalesAgreement(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber/sales-agreement")

        vaultQueryHelper {
            val agreementStateAndInstant: StateAndInstant<LandAgreementState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return 404 if null
            agreementStateAndInstant ?: return ResponseEntity.notFound().build()

            //Build the DTO
            val salesAgreementDTO = agreementStateAndInstant.state.toDTO(agreementStateAndInstant.instant?.toLocalDateTime())

            //Return the DTO
            return ResponseEntity.ok().body(salesAgreementDTO)
        }
    }

    /**
     * Signs or finalises a sales agreement
     */
    @PutMapping(value = "/titles/{title-number}/sales-agreement",
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun updateSalesAgreement(@PathVariable("title-number") titleNumber: String, @RequestBody input: SalesAgreementSignDTO): ResponseEntity<Any?> {
        logger.info("PUT /titles/$titleNumber/sales-agreement")

        val agreement = vaultQueryHelper {
            val agreementStateAndInstant: StateAndInstant<LandAgreementState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return 404 if null
            agreementStateAndInstant?.state ?: return ResponseEntity.notFound().build()
        }

        //Return 401 if unauthorised
        if (myIdentity != agreement.buyerConveyancer && myIdentity != agreement.sellerConveyancer)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        input.signatory ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signatory is null!")

        if (myIdentity.toDTO() == input.signatory) {
            //start flow
            return when (input.action) {
                "approve" -> {
                    responseEntityFromFlowHandle {
                        rpcOps.startFlowDynamic(
                                ApproveAgreementFlow::class.java,
                                agreement.linearId.toString()
                        )
                    }
                }
                "sign" -> {
                    if (input.signatory_individual == agreement.seller.toDTO() && myIdentity == agreement.sellerConveyancer) {
                        agreement.sign("seller", titleNumber, SellerSignAgreementFlow::class.java)
                    } else if (input.signatory_individual == agreement.buyer.toDTO() && myIdentity == agreement.buyerConveyancer) {
                        agreement.sign("buyer", titleNumber, BuyerSignAgreementFlow::class.java)
                    } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signatory_individual.")
                }
                else -> return ResponseEntity.badRequest().body("Invalid action.")
            }
        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signatory!")
    }

    /**
     * Sign sales agreement
     */
    private fun <T> LandAgreementState.sign(user: String, signMessage: String, flow: Class<out FlowLogic<T>>): ResponseEntity<Any?> {
        val signature = KeyUtils(KeyUtils.DEFAULT_KEY_PROPERTIES_FILE).sign(user, signMessage)

        return responseEntityFromFlowHandle {
            rpcOps.startFlowDynamic(
                    flow,
                    this.linearId.toString(),
                    signature
            )
        }
    }

    /**
     * Drafts a new sales agreement
     */
    @PostMapping(value = "/titles/{title-number}/sales-agreement",
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun addSalesAgreementToTitle(@PathVariable("title-number") titleNumber: String, @RequestBody input: SalesAgreementDTO): ResponseEntity<Any?> {
        logger.info("POST /titles/$titleNumber/sales-agreement")

        val agreementState = vaultQueryHelper {
            //Get details of title
            val landTitle: StateAndInstant<LandTitleState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return 404 if null
            landTitle ?: return ResponseEntity.notFound().build()

            //Get buyer conveyancer party
            val buyerConveyancerParty = input.buyer_conveyancer.toWellKnownParty()
                    ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Buyer conveyancer party information is invalid.")

            //Build state
            landTitle.state.run {
                LandAgreementState(
                        titleNumber,
                        input.buyer.toCustomParty(
                                true,
                                KeyUtils(KeyUtils.DEFAULT_KEY_PROPERTIES_FILE).readPublicKey("buyer"),
                                null
                        ),
                        landTitleProperties.owner,
                        buyerConveyancerParty,
                        myIdentity,
                        LocalDate.now(),
                        input.completion_date.toInstant(),
                        input.contract_rate,
                        getAmount(input.purchase_price, input.purchase_price_currency_code),
                        getAmount(input.deposit, input.deposit_currency_code),
                        getAmount(input.contents_price, input.contents_price_currency_code),
                        getAmount(input.balance, input.balance_currency_code),
                        linearId.toString(),
                        listOf(),
                        when (input.guarantee) {
                            "full" -> TitleGuarantee.FULL
                            "limited" -> TitleGuarantee.LIMITED
                            else -> return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Title guarantee is invalid.")
                        },
                        AgreementStatus.CREATED,
                        false
                )
            }
        }

        return responseEntityFromFlowHandle {
            it.startFlowDynamic(
                    DraftAgreementFlow::class.java,
                    agreementState,
                    agreementState.buyerConveyancer
            )
        }
    }

    /**
     * Gets the restrictions on a title
     */
    @GetMapping(value = "/titles/{title-number}/restrictions",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getRestrictionsOnTitle(@PathVariable("title-number") titleNumber: String,
                               @RequestParam("type", required = false) restrictionType: String?): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber/restrictions")

        vaultQueryHelper {
            //Get State and Instant
            val chargesAndRestrictionsStateAndInstant: StateAndInstant<ProposedChargesAndRestrictionsState>? = getStateBy { it.state.data.titleID == titleNumber }

            val restrictions = chargesAndRestrictionsStateAndInstant.let { it ->
                // Either return restrictions or empty array
                it?.state?.restrictions ?: return ResponseEntity.ok().body(listOf<Unit>())
            }.let { restrictions ->
                //Filter by restriction type if applicable
                if (restrictionType == null) restrictions else {
                    restrictions.filter { restriction ->
                        when (restriction) {
                            is ChargeRestriction -> restrictionType == ChargeRestrictionDTO.RESTRICTION_TYPE
                            else /*is Restriction*/ -> restrictionType == RestrictionDTO.RESTRICTION_TYPE
                        }
                    }
                }
            }

            //Return Restrictions DTO
            return ResponseEntity.ok().body(restrictions.map { it.toDTO() })
        }
    }

    /**
     * Adds a restriction to the title
     */
    @PostMapping(value = "/titles/{title-number}/restrictions",
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun addRestrictionsToTitle(@PathVariable("title-number") titleNumber: String, @RequestBody input: RestrictionDTO): ResponseEntity<Any?> {
        logger.info("POST /titles/$titleNumber/restrictions")

        val restriction = try {
            input.toState { it.toWellKnownParty() }
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)
        }

        val chargesAndRestrictionsState = vaultQueryHelper {
            //Get details of charges and restrictions
            val stateAndInstant: StateAndInstant<ProposedChargesAndRestrictionsState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return 404 if null
            stateAndInstant ?: return ResponseEntity.notFound().build()

            stateAndInstant.state
        }

        return responseEntityFromFlowHandle {
            it.startFlowDynamic(
                    AddNewChargeFlow::class.java,
                    chargesAndRestrictionsState.linearId.toString(),
                    setOf(restriction),
                    setOf<Charge>()
            )
        }
    }

    /**
     * Gets the charge on a title
     */
    @GetMapping(value = "/titles/{title-number}/charges",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getChargesOnTitle(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber/charges")

        vaultQueryHelper {
            //Get State and Instant
            val chargesAndRestrictionsStateAndInstant: StateAndInstant<ProposedChargesAndRestrictionsState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return empty array if null
            chargesAndRestrictionsStateAndInstant ?: return ResponseEntity.ok().body(listOf<Unit>())

            val state = chargesAndRestrictionsStateAndInstant.state

            val charges = state.charges.toSet() +
                    state.restrictions.asSequence()
                            .filterIsInstance<ChargeRestriction>()
                            .map { it.charge }
                            .toSet()

            //Build the DTOs
            val chargesDTO = charges.map { it.toDTO() }

            //Return the DTOs
            return ResponseEntity.ok().body(chargesDTO)
        }
    }

    /**
     * Requests discharge
     */
    @PutMapping(value = "/titles/{title-number}/charges",
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun updateCharges(@PathVariable("title-number") titleNumber: String, @RequestBody input: ChargesUpdateDTO): ResponseEntity<Any?> {
        logger.info("PUT /titles/$titleNumber/charges")

        val landTitle = vaultQueryHelper {
            //Get details of title
            val landTitle: StateAndInstant<LandTitleState>? = getStateBy { it.state.data.titleID == titleNumber }

            //Return 404 if null
            landTitle ?: return ResponseEntity.notFound().build()
        }

        return when (input.action) {
            "request_discharge" -> {
                responseEntityFromFlowHandle {
                    it.startFlowDynamic(
                            RequestForDischargeFlow::class.java,
                            landTitle.state.linearId.toString()
                    )
                }
            }
            else -> ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
        }
    }

    /**
     * Returns all titles
     */
    @GetMapping(value = "/titles", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getTitles(): ResponseEntity<Any?> {
        logger.info("GET /titles")

        vaultQueryHelper {
            //Build Title Transfer DTOs
            val titleTransferDTO = buildTitleTransferDTOs()

            //Return Title Transfer DTOs
            return ResponseEntity.ok().body(titleTransferDTO)
        }
    }

    /**
     * Returns a title
     */
    @GetMapping(value = "/titles/{title-number}", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getTitle(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber")

        vaultQueryHelper {
            //Build Title Transfer DTO
            val titleTransferDTO = buildTitleTransferDTO(titleNumber)

            //Return 404 if null
            titleTransferDTO ?: return ResponseEntity.notFound().build()

            //Return Title Transfer DTO
            return ResponseEntity.ok().body(titleTransferDTO)
        }
    }

}
