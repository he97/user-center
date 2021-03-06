package com.mall.user_center.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mall.user_center.dao.user_center.*;
import com.mall.user_center.domain.dto.Commodity.CartCommoditiesDTO;
import com.mall.user_center.domain.dto.Commodity.CommodityInfoRespDto;
import com.mall.user_center.domain.dto.transaction.BoughtCommodityInfoDto;
import com.mall.user_center.domain.dto.transaction.TransactionCommodityDto;
import com.mall.user_center.domain.dto.messaging.AlterTransactionDto;
import com.mall.user_center.domain.dto.order.HandleOrderResp;
import com.mall.user_center.domain.dto.order.UserTransactionDto;
import com.mall.user_center.domain.dto.transaction.CancelTransactionDto;
import com.mall.user_center.domain.dto.transaction.StartCancelTranDto;
import com.mall.user_center.domain.dto.user.RespDto;
import com.mall.user_center.domain.entity.commodity_center.TransactionMessageDto;
import com.mall.user_center.domain.entity.user_center.*;
import com.mall.user_center.domain.enums.TransactionCommodityMessageStatusEnum;
import com.mall.user_center.feignClient.CommodityCenterFeignClient;
import com.mall.user_center.rocketmq.MySource;
import com.mall.user_center.utils.JwtOperator;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Slf4j
public class HandleOrderService {
    private final UserMapper userMapper;
    private final ShoppingCartMapper shoppingCartMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final TransactionListMapper transactionListMapper;
    private final TransactionLogMapper transactionLogMapper;
    private final MySource mySource;
    private final UserInformationMapper userInformationMapper;
    private final JwtOperator jwtOperator;
    private final UserService userService;
    private final CommodityCenterFeignClient commodityCenterFeignClient;
    /**
     * ???token?????????id
     * @param token
     * @return
     */
    private String getTokenId(String token) {
        Claims claimsFromToken = this.jwtOperator.getClaimsFromToken(token);
        String tokenId;
        tokenId = (String) claimsFromToken.get("id");
        return tokenId;
    }
    private String getToken() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = servletRequestAttributes.getRequest();
        return request.getHeader("X-Token");
    }
    public HandleOrderResp handleOrder(UserTransactionDto userTransactionDto) {
        log.info("userTransactionDto in handleOrder:{}",userTransactionDto);
        try {
//        1,????????????id
            String token = this.getToken();
            String tokenId = this.getTokenId(token);
            User user = this.userMapper.selectByPrimaryKey(tokenId);
            UserInformation userInformation = this.userInformationMapper.selectByPrimaryKey(tokenId);

            if (userInformation.getUserBalance()<userTransactionDto.getPrice()){
                throw new IllegalStateException("????????????");
            }else {
                userInformation.setUserBalance(userInformation.getUserBalance() - userTransactionDto.getPrice());
                this.userInformationMapper.updateByPrimaryKey(userInformation);
            }
//        2??????????????????????????????????????????????????????????????????
//            ???????????????
            userInformation.setUserBalance(userInformation.getUserBalance() - userTransactionDto.getPrice());

            List<CartCommoditiesDTO> hadSelectedCommodity = userTransactionDto.getCommodities();
            ShoppingCart shoppingCart = this.shoppingCartMapper.selectByPrimaryKey(tokenId);
            List<CartCommoditiesDTO> commoditiesDTOS = JSONArray.parseArray(shoppingCart.getCommodities(),CartCommoditiesDTO.class);

            List<CartCommoditiesDTO> updatedList = new ArrayList<CartCommoditiesDTO>();
//            ???????????????????????????
            updatedList = getExceptedLIst(commoditiesDTOS, hadSelectedCommodity, updatedList);

            log.info("updateList:{}",updatedList);
            ShoppingCart newShoppingCart = ShoppingCart.builder()
                    .userId(tokenId)
                    .commodities(JSONArray.parseArray(JSONArray.toJSONString(updatedList)).toJSONString())
                    .build();
            log.info("new shopping cart:{}",newShoppingCart);
            this.shoppingCartMapper.updateByPrimaryKey(newShoppingCart);
//            ???????????????????????????
            List<TransactionCommodityDto> transactionCommodityDtos = this.toTransactionCommodities(hadSelectedCommodity);


//            ??????????????????
            String transactionId = UUID.randomUUID().toString();
//          ???????????????
            Date myTime = getTime();
            TransactionList transaction = TransactionList.builder()
                    .commodities(JSONObject.toJSONString(transactionCommodityDtos))
                    .transactionId(transactionId)
                    .transactionTime(myTime)
                    .transactionInfo(userTransactionDto.getInfo())
                    .transactionPhone(userTransactionDto.getPhone())
                    .transactionAddress(userTransactionDto.getAddress())
                    .userId(tokenId)
                    .transactionPrice(userTransactionDto.getPrice())
                    .build();
            userTransactionDto.setStatus(TransactionCommodityMessageStatusEnum.HAD_BOUGHT.toString());
            this.transactionListMapper.insertSelective(transaction);
//            ?????????commodity?????????????????????
            this.mySource.output()
                    .send(
                            MessageBuilder
                                    .withPayload(
                                        TransactionMessageDto.builder()
                                                .status(String.valueOf(TransactionCommodityMessageStatusEnum.HAD_BOUGHT))
                                                .commodities(userTransactionDto.getCommodities())
                                                .buyerId(tokenId)
                                                .transactionId(transactionId)
                                                .build()
                                    )
                                    .setHeader("X-token",token)
                                    .setHeader(RocketMQHeaders.TRANSACTION_ID,transactionId)
                                    .setHeader("userId",tokenId)
                                    .build()
                    );

        } catch (Exception e) {
            e.printStackTrace();
            return HandleOrderResp.builder()
                    .message(e.getMessage())
                    .status("500")
                    .build();
        }
        return HandleOrderResp.builder()
                .message("????????????")
                .status("200")
                .build();
//        3???????????????
//        4??????????????????log???????????????????????????

    }

    private Date getTime() throws ParseException {
        SimpleDateFormat sdf = getSimpleDateFormat();
        Date date = new Date();
        String nowTime = sdf.format(date);
        Date myTime = sdf.parse(nowTime);
        return myTime;
    }

    private List<TransactionCommodityDto> toTransactionCommodities(List<CartCommoditiesDTO> commoditiesDTOS) {
        ArrayList<TransactionCommodityDto> transactionCommodityDtos = new ArrayList<>();
        for (CartCommoditiesDTO commodity :
                commoditiesDTOS) {
            CommodityInfoRespDto commodityInfoById = this.commodityCenterFeignClient.getCommodityInfoById(commodity.getCommodityId());
            String ownerId = commodityInfoById.getOwnerId();
            if(commodityInfoById.getCommodityRemainder() < commodity.getCount()){
                throw new IllegalStateException("????????????");
            }
            TransactionCommodityDto transactionCommodityDto = new TransactionCommodityDto();
            BeanUtils.copyProperties(commodity,transactionCommodityDto);
            transactionCommodityDto.setOwnerId(ownerId);
            transactionCommodityDto.setCommodityStatus(TransactionCommodityMessageStatusEnum.HAD_BOUGHT.toString());
            transactionCommodityDtos.add(transactionCommodityDto);
        }
        return transactionCommodityDtos;
    }

    public HandleOrderResp cancelTransaction(CancelTransactionDto cancelTransactionDto) {
        try {
//        ????????????
//        1,??????????????????????????????
            String token = this.getToken();
            String tokenId = getTokenId(token);
            String logMessage = "";
            Integer count = 0;
            String role = "user";
            Date time = this.getTime();
//            ?????????????????????????????????????????????
            TransactionList transactionList = this.transactionListMapper.selectByPrimaryKey(cancelTransactionDto.getTransactionId());
            List<TransactionCommodityDto> transactionCommodityDtos = JSONArray.parseArray(transactionList.getCommodities(), TransactionCommodityDto.class);
            for (TransactionCommodityDto tranCommo :
                    transactionCommodityDtos) {
                if(tranCommo.getCommodityStatus().equals(TransactionCommodityMessageStatusEnum.WAIT_CANCEL_CONFIRM.name())){
//                    ??????????????????????????????????????????
                    if (cancelTransactionDto.getCommodityId().equals(tranCommo.getCommodityId())) {
                        count = tranCommo.getCount();
                        if ("AGREE".equals(cancelTransactionDto.getType())) {
                            tranCommo.setCommodityStatus(TransactionCommodityMessageStatusEnum.USER_AGREE_CANCEL.name());
                            String buyerId = transactionList.getUserId();
                            UserInformation userInformation = this.userInformationMapper.selectByPrimaryKey(buyerId);
                            userInformation.setUserBalance(userInformation.getUserBalance() + tranCommo.getPrice() * tranCommo.getCount());
                            logMessage = "???????????????????????????????????????id???:" + cancelTransactionDto.getCommodityId();
                            this.transactionLogMapper.updateByPrimaryKey(
                                    TransactionLog.builder()
                                            .userId(tokenId)
                                            .transactionStatus(TransactionCommodityMessageStatusEnum.USER_AGREE_CANCEL.toString())
                                            .transactionId(cancelTransactionDto.getTransactionId())
                                            .time(time)
                                            .log(logMessage)
                                            .build()
                            );
                        } else if ("REJECT".equals(cancelTransactionDto.getType())) {
                            tranCommo.setCommodityStatus(TransactionCommodityMessageStatusEnum.USER_REJECT_CANCEL.name());
                            logMessage = "???????????????????????????????????????id???:" + cancelTransactionDto.getCommodityId();
                            this.transactionLogMapper.updateByPrimaryKey(
                                    TransactionLog.builder()
                                            .userId(tokenId)
                                            .transactionStatus(TransactionCommodityMessageStatusEnum.USER_AGREE_CANCEL.toString())
                                            .transactionId(cancelTransactionDto.getTransactionId())
                                            .time(time)
                                            .log(logMessage)
                                            .build()
                            );
                        } else {
                            throw new IllegalStateException("???????????????type??????");
                        }
                    }
                }

            }
            if (logMessage.isEmpty()){
                throw new IllegalStateException("??????????????????????????????");
            }
            transactionList.setCommodities(JSONArray.toJSONString(transactionCommodityDtos));
            this.transactionListMapper.updateByPrimaryKey(transactionList);
            handleCancelSendMessage(cancelTransactionDto, token, tokenId, count, role);

            HandleOrderResp.HandleOrderRespBuilder builder = HandleOrderResp.builder();
            builder.status("200");
            builder.message(logMessage);
            return builder
                    .build();
        } catch (ParseException e) {
            e.printStackTrace();
            throw new IllegalStateException("zhauntaibudui");
        }
    }

    private void handleCancelSendMessage(CancelTransactionDto cancelTransactionDto, String token, String tokenId, Integer count, String role) {
        this.mySource.alterTransaction().send(
                MessageBuilder
                        .withPayload(
                                AlterTransactionDto.builder()
                                        .commodityId(cancelTransactionDto.getCommodityId())
                                        .count(count)
                                        .transactionId(cancelTransactionDto.getTransactionId())
                                        .toStatus(cancelTransactionDto.getType())
                                        .handleRole(role)
                                        .build()
                        )
                        .setHeader("X-token", token)
                        .setHeader(RocketMQHeaders.TRANSACTION_ID, cancelTransactionDto.getTransactionId())
                        .setHeader("userId", tokenId)
                        .build()
        );
    }


    private List<CartCommoditiesDTO> getExceptedLIst(List<CartCommoditiesDTO> commoditiesDTOS, List<CartCommoditiesDTO> hadSelectedCommodity, List<CartCommoditiesDTO> updatedList) {
        for (CartCommoditiesDTO commodity :
                commoditiesDTOS) {
            Boolean tag = false;
            for (CartCommoditiesDTO selectedCommodity :
                    hadSelectedCommodity) {
                if (commodity.getCommodityId().equals(selectedCommodity.getCommodityId())) {
                    tag = true;
                }
            }
            if(!tag){
                //                ???????????????
                updatedList.add(commodity);
            }
        }
        return updatedList;
    }

    private SimpleDateFormat getSimpleDateFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat();
        // a???am/pm?????????
        sdf.applyPattern("yyyy-MM-dd :HH:mm:ss");
        return sdf;
    }

    @Transactional(rollbackFor = Exception.class)
    public void auditOrderByDB(String userId,String transactionId) {
        TransactionList transactionList = this.transactionListMapper.selectByPrimaryKey(transactionId);
        if(!transactionList.getUserId().equals(userId)){
            throw new IllegalStateException("??????????????????");
        }
        log.info("?????????????????????");
    }

    @Transactional(rollbackFor = Exception.class)
    public void auditByIdWithMqLog(String userId,String transactionId) {
        this.auditOrderByDB(userId, transactionId);
        this.transactionLogMapper.insertSelective(
                TransactionLog.builder()
                        .log("?????????????????????")
                        .transactionId(transactionId)
                        .transactionStatus(TransactionCommodityMessageStatusEnum.HAD_BOUGHT.toString())
                        .userId(userId)
                        .build()
        );
    }

    public RespDto startCancelTransaction(StartCancelTranDto startCancelTranDto) {
        try {
            TransactionList transactionList = this.transactionListMapper.selectByPrimaryKey(startCancelTranDto.getTransactionId());
            TransactionLog transactionLog = this.transactionLogMapper.selectByPrimaryKey(startCancelTranDto.getTransactionId());
            List<TransactionCommodityDto> transactionCommodityDtos = JSONArray.parseArray(transactionList.getCommodities(), TransactionCommodityDto.class);
            String logMessage = "";
            for (TransactionCommodityDto transactionCommodityDto:
                 transactionCommodityDtos) {
                if(transactionCommodityDto.getCommodityId().equals(startCancelTranDto.getCommodityId())){
                    transactionCommodityDto.setCommodityStatus(TransactionCommodityMessageStatusEnum.WAIT_CANCEL_CONFIRM.name());
                    logMessage = transactionCommodityDto.getCommodityName() + "????????????";
                    break;
                }
            }
            transactionLog.setLog(transactionLog.getLog() + logMessage);
            this.transactionLogMapper.updateByPrimaryKey(transactionLog);
            transactionList.setCommodities(JSONArray.toJSONString(transactionCommodityDtos));
            this.transactionListMapper.updateByPrimaryKey((transactionList));
            return RespDto.builder()
                    .message("??????????????????")
                    .status("200")
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return RespDto.builder()
                    .message("????????????????????????????????????????????????????????????")
                    .status("500")
                    .build();
        }
    }

    public HandleOrderResp adminHandleOrder(CancelTransactionDto cancelTransactionDto) {
        try {
//        ????????????
//        1,??????????????????????????????
            String token = this.getToken();
            String tokenId = getTokenId(token);
            Date time = this.getTime();
            String logMessage = "";
            Integer count = 0;
            String role = "admin";
//            ?????????????????????????????????????????????
            TransactionList transactionList = this.transactionListMapper.selectByPrimaryKey(cancelTransactionDto.getTransactionId());
            List<TransactionCommodityDto> transactionCommodityDtos = JSONArray.parseArray(transactionList.getCommodities(), TransactionCommodityDto.class);
            for (TransactionCommodityDto tranCommo :
                    transactionCommodityDtos) {
                if(tranCommo.getCommodityStatus().equals(TransactionCommodityMessageStatusEnum.WAIT_CANCEL_CONFIRM.name())){
//                    ??????????????????????????????????????????
                    if (cancelTransactionDto.getCommodityId().equals(tranCommo.getCommodityId())) {
                        count = tranCommo.getCount();
                        if ("AGREE".equals(cancelTransactionDto.getType())) {
                            tranCommo.setCommodityStatus(TransactionCommodityMessageStatusEnum.USER_AGREE_CANCEL.name());
                            String buyerId = transactionList.getUserId();
                            UserInformation userInformation = this.userInformationMapper.selectByPrimaryKey(buyerId);
                            userInformation.setUserBalance(userInformation.getUserBalance() + tranCommo.getPrice() * tranCommo.getCount());
                            logMessage = "??????????????????????????????????????????id???:" + cancelTransactionDto.getCommodityId();
                            this.transactionLogMapper.updateByPrimaryKey(
                                    TransactionLog.builder()
                                            .transactionStatus(TransactionCommodityMessageStatusEnum.ADMIN_AGREE_CONFIRM.name())
                                            .transactionId(cancelTransactionDto.getTransactionId())
                                            .time(time)
                                            .log(logMessage)
                                            .build()
                            );
                        } else if ("REJECT".equals(cancelTransactionDto.getType())) {
                            tranCommo.setCommodityStatus(TransactionCommodityMessageStatusEnum.USER_REJECT_CANCEL.name());
                            logMessage = "??????????????????????????????????????????id???:" + cancelTransactionDto.getCommodityId();
                            this.transactionLogMapper.updateByPrimaryKey(
                                    TransactionLog.builder()
                                            .transactionStatus(TransactionCommodityMessageStatusEnum.ADMIN_REJECT_CONFIRM.name())
                                            .transactionId(cancelTransactionDto.getTransactionId())
                                            .time(time)
                                            .log(logMessage)
                                            .build()
                            );
                        } else {
                            throw new IllegalStateException("???????????????type??????");
                        }
                    }
                }

            }
            if (logMessage.isEmpty()){
                throw new IllegalStateException("??????????????????????????????");
            }
            transactionList.setCommodities(JSONArray.toJSONString(transactionCommodityDtos));
            this.transactionListMapper.updateByPrimaryKey(transactionList);
//          ????????????
            handleCancelSendMessage(cancelTransactionDto, token, tokenId, count, role);
            return HandleOrderResp.builder()
                    .message(logMessage)
                    .status("200")
                    .build();
        } catch (ParseException e) {
            e.printStackTrace();
            throw new IllegalStateException("zhauntaibudui");
        }

    }
}

