package com.mall.user_center.service;

import cn.binarywang.wx.miniapp.api.WxMaService;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mall.user_center.dao.user_center.*;
import com.mall.user_center.domain.dto.Commodity.CartCommoditiesDTO;
import com.mall.user_center.domain.dto.Commodity.CommodityInfoRespDto;
import com.mall.user_center.domain.dto.Commodity.SelectedCommodityDTO;
import com.mall.user_center.domain.dto.cartDto.AddCartRespDto;
import com.mall.user_center.domain.dto.cartDto.AlterCartDto;
import com.mall.user_center.domain.dto.cartDto.RemoveCartDto;
import com.mall.user_center.domain.dto.cartDto.RemoveCartRespDto;
import com.mall.user_center.domain.dto.transaction.BoughtCommodityInfoDto;
import com.mall.user_center.domain.dto.user.*;
import com.mall.user_center.domain.entity.user_center.*;
import com.mall.user_center.domain.enums.CommodityEnum;
import com.mall.user_center.domain.enums.TransactionCommodityMessageStatusEnum;
import com.mall.user_center.feignClient.CommodityCenterFeignClient;
import com.mall.user_center.utils.JwtOperator;
import io.jsonwebtoken.Claims;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import sun.util.resources.CalendarData;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Slf4j
public class UserService {
    private final UserMapper userMapper;
    private final UserInformationMapper userInformationMapper;
    private final WxMaService wxMaService;
    private final WeixinInfoMapper weixinInfoMapper;
    private final ShoppingCartMapper shoppingCartMapper;
    private final JwtOperator jwtOperator;
    private final CommodityCenterFeignClient commodityCenterFeignClient;
    private final UserAndCommodityService userAndCommodityService;
    private final TransactionListMapper transactionListMapper;

    public User findById(Integer id) {
        return this.userMapper.selectByPrimaryKey(id);
    }

    public User logIn(UserLogInDTO userLogInDTO,String openId){
//        WeixinInfo weixinInfo = this.weixinInfoMapper.selectByPrimaryKey(openId);
        if(this.weixinInfoMapper.existsWithPrimaryKey(openId)){
            log.info("????????????");
        }else {
            createNewUser(userLogInDTO,openId);
        }
        WeixinInfo weixinInfo = this.weixinInfoMapper.selectByPrimaryKey(openId);
        User user = this.userMapper.selectByPrimaryKey(weixinInfo.getUserId());

        return user;
    }

    private boolean createNewUser(UserLogInDTO userLogInDTO, String openId) {
        try {
            User user = User.builder()
                            .userId(UUID.randomUUID().toString())
    //                ????????????defaultpwd
                            .userPassword(LocalTime.now().toString())
                            .userValid(true)
                            .build();
//        ??????
            WeixinInfo weixinInfo = WeixinInfo.builder()
                    .userId(user.getUserId())
                    .weixinAvatuaurl(userLogInDTO.getAvatarUrl())
                    .weixinCode(openId)
                    .weixinNickname(userLogInDTO.getWxNickName())
                    .build();
            this.weixinInfoMapper.insertSelective(weixinInfo);
            this.userMapper.insertSelective(user);
            log.info("???????????????user:{}",user);
            return true;
        } catch (Exception e) {
            log.info("??????????????????");
            e.printStackTrace();
//            TODO ??????????????????????????????
            return false;
        }
    }
    public List<CartCommoditiesDTO> getShoppingCartCommodities(String userId){
//        ????????????userid????????????
        return getCommoditiesDTOS(userId);
    }

    private List<CartCommoditiesDTO> getCommoditiesDTOS(String userId) {
        ShoppingCart shoppingCart = this.shoppingCartMapper.selectByPrimaryKey(userId);
//        ArrayList<CartCommoditiesDTO> cartCommoditiesDTOArrayList= shoppingCart.getCommodities();
        JSONArray cartCommoditiesDtoJsonArray = (JSONArray) JSONArray.parse(shoppingCart.getCommodities());
        List<CartCommoditiesDTO> cartCommoditiesDTOS = cartCommoditiesDtoJsonArray.toJavaList(CartCommoditiesDTO.class);
        for (CartCommoditiesDTO commodity :
                cartCommoditiesDTOS) {
            if(!commodity.getCloud()){
                commodity.setCommodityFirstPictureUrl(commodity.getCommodityFirstPictureUrl());
            }

        }
        return cartCommoditiesDTOS;
    }
    public List<CartCommoditiesDTO> getUserSelected(SelectedCommodityDTO idList){
        String token = idList.getToken();
        String tokenId = getTokenId(token);
        String list = idList.getList();
        log.info("idList:{}", list);
        String[] strings = list.split(",");
        List<CartCommoditiesDTO> cartCommoditiesDTOS = this.getShoppingCartCommodities(tokenId);
        List<CartCommoditiesDTO> selectedCommodities = new ArrayList<CartCommoditiesDTO>();
        boolean mark = false;
        for (CartCommoditiesDTO commodity :
                cartCommoditiesDTOS) {
            for (String id :
                    strings) {
                if(id.equals(commodity.getCommodityId())){
                    selectedCommodities.add(commodity);
                    mark = true;
                }
            }
        }
        if(!mark){
            for (String id :
                    strings) {
                CommodityInfoRespDto commodityInfoById = this.commodityCenterFeignClient.getCommodityInfoById(id);
                CartCommoditiesDTO cartCommoditiesDTO = new CartCommoditiesDTO();
                BeanUtils.copyProperties(commodityInfoById,cartCommoditiesDTO);
                cartCommoditiesDTO.setCommodityFirstPictureUrl(commodityInfoById.getCommodityPictureUrl().get(0));
                cartCommoditiesDTO.setCount(1);
                selectedCommodities.add(cartCommoditiesDTO);
            }
        }
        log.info("selected:{}",cartCommoditiesDTOS);
        return selectedCommodities;
    }

    public List<CartCommoditiesDTO> justBuyGetCommodity(SelectedCommodityDTO idList) {
        String token = idList.getToken();
        String tokenId = getTokenId(token);
        String list = idList.getList();
        log.info("idList:{}", list);
        String[] strings = list.split(",");
//        ???commodity??????????????????

        List<CartCommoditiesDTO> cartCommoditiesDTOS = new ArrayList<CartCommoditiesDTO>();
        for (String id :
                strings) {
            CommodityInfoRespDto commodityInfoById = this.commodityCenterFeignClient.getCommodityInfoById(id);
            CartCommoditiesDTO cartCommoditiesDTO = new CartCommoditiesDTO();
            BeanUtils.copyProperties(commodityInfoById,cartCommoditiesDTO);
            cartCommoditiesDTO.setCommodityFirstPictureUrl(commodityInfoById.getCommodityPictureUrl().get(0));
            cartCommoditiesDTO.setCount(1);
            cartCommoditiesDTOS.add(cartCommoditiesDTO);
        }

//        List<CartCommoditiesDTO> selectedCommodities = new ArrayList<CartCommoditiesDTO>();
//        for (CartCommoditiesDTO commodity :
//                cartCommoditiesDTOS) {
//            for (String id :
//                    strings) {
//                if(id.equals(commodity.getCommodityId())){
//                    selectedCommodities.add(commodity);
//                }
//            }
//        }
        log.info("selected:{}",cartCommoditiesDTOS);
        return cartCommoditiesDTOS;
    }

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

    public AddCartRespDto addCartByCommodityId(String commodityId) {
        Boolean result = true;
        try {
            String token = this.getToken();
            String userId = this.getTokenId(token);
            ShoppingCart shoppingCart = this.shoppingCartMapper.selectByPrimaryKey(userId);

            String commodities = shoppingCart.getCommodities();
            JSONArray cart = (JSONArray) JSONArray.parse(commodities);
            List<CartCommoditiesDTO> cartCommoditiesDTOS = cart.toJavaList(CartCommoditiesDTO.class);
            CommodityInfoRespDto commodityInfoById = this.commodityCenterFeignClient.getCommodityInfoById(commodityId);
//        ????????????????????????????????????
            Boolean tag = false;
            for (CartCommoditiesDTO cartcommmodity :
                    cartCommoditiesDTOS) {
                if (cartcommmodity.getCommodityId().equals(commodityInfoById.getCommodityId())) {
                    cartcommmodity.setCount(cartcommmodity.getCount() + 1);
                    tag = true;
                    break;
                }
            }
//        ???????????????????????????????????????
            if(!tag){
                CartCommoditiesDTO cartCommoditiesDTO = new CartCommoditiesDTO();
                BeanUtils.copyProperties(commodityInfoById,cartCommoditiesDTO);
                cartCommoditiesDTO.setCount(1);
                cartCommoditiesDTO.setCommodityRemainder(commodityInfoById.getCommodityRemainder());
                cartCommoditiesDTO.setCommodityFirstPictureUrl(commodityInfoById.getCommodityPictureUrl().get(0));
                cartCommoditiesDTOS.add(cartCommoditiesDTO);
            }
            String updatedString = JSONObject.toJSONString(cartCommoditiesDTOS);
            shoppingCart.setCommodities(updatedString);
            this.shoppingCartMapper.updateByPrimaryKey(shoppingCart);
        } catch (Exception e) {
            e.printStackTrace();
            result = false;
        }
        if(result){
            return AddCartRespDto.builder()
                    .status("200")
                    .message("ok")
                    .build();
        }else {
            return AddCartRespDto.builder()
                    .status("500")
                    .message("not ok")
                    .build();
        }
    }
    public RemoveCartRespDto removeCartByCommodityId(RemoveCartDto removeCartDto) {
//
        try {
            String token = this.getToken();
            String userId = this.getTokenId(token);
            ShoppingCart shoppingCart = this.shoppingCartMapper.selectByPrimaryKey(userId);
            String commodityId = removeCartDto.getCommodityId();
            String commodities = shoppingCart.getCommodities();
            JSONArray cart = (JSONArray) JSONArray.parse(commodities);
            List<CartCommoditiesDTO> cartCommoditiesDTOS = cart.toJavaList(CartCommoditiesDTO.class);
            cartCommoditiesDTOS.removeIf(cartCommodity -> commodityId.equals(cartCommodity.getCommodityId()));
            String updatedString = JSONObject.toJSONString(cartCommoditiesDTOS);
            shoppingCart.setCommodities(updatedString);
            this.shoppingCartMapper.updateByPrimaryKey(shoppingCart);
            return RemoveCartRespDto.builder()
                    .status("200")
                    .message("????????????")
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return RemoveCartRespDto.builder()
                    .message("???????????????????????????????????????")
                    .status("500")
                    .build();
        }


    }

    public UserInfoRespDto getUserInfoById() {
        try {
            String token = this.getToken();
            String tokenId = this.getTokenId(token);
            User user = this.userMapper.selectByPrimaryKey(tokenId);
            UserInformation userInformation = this.userInformationMapper.selectByPrimaryKey(tokenId);
//            ????????????
            if(!user.getUserValid()){
                throw new IllegalStateException("??????????????????");
            }
            UserInfoRespDto userInfoRespDto = new UserInfoRespDto();
            BeanUtils.copyProperties(userInformation,userInfoRespDto,"userBirthdate");
            userInfoRespDto.setUserBirthdate(this.getSimpleDateFormat().format(userInformation.getUserBirthdate()));
            userInfoRespDto.setValid(user.getUserValid());
            userInfoRespDto.setMessage("ok");
            userInfoRespDto.setStatus("200");
            return userInfoRespDto;
        } catch (Exception e) {
            e.printStackTrace();
            return UserInfoRespDto.builder()
                    .status("500")
                    .message(e.getMessage())
                    .build();
        }

    }

    /**
     * ??????????????????
     * @param alterUserInfoDto
     * @return
     */
    public RespDto alterUserInfo(AlterUserInfoDto alterUserInfoDto) throws ParseException {
        try {
            SimpleDateFormat sdf = this.getSimpleDateFormat();
            Calendar instance = Calendar.getInstance();
            instance.setTime(alterUserInfoDto.getUserBirthdate());
            int year = instance.get(Calendar.YEAR);
            Calendar nowTime = Calendar.getInstance();
            Date time = this.getTime();
            nowTime.setTime(time);
            int nowYear = nowTime.get(Calendar.YEAR);
            int age = nowYear - year;
            Date parse = instance.getTime();

            UserInformation build = UserInformation.builder()
                    .userId(this.getTokenId(this.getToken()))
                    .userBirthdate(parse)
                    .userAge(age)
                    .userSex(alterUserInfoDto.getUserSex())
                    .userName(alterUserInfoDto.getUserName())
                    .userPhone(alterUserInfoDto.getUserPhone())
                    .build();
            this.userInformationMapper.updateByPrimaryKeySelective(build);
            return RespDto.builder()
                    .status("200")
                    .message("??????????????????")
                    .build();
        } catch (Exception e) {
            return RespDto.builder()
                    .status("500")
                    .message("????????????????????????????????????")
                    .build();
        }

    }

    private Date getTime() throws ParseException {
        SimpleDateFormat sdf = getSimpleDateFormat();
        Date date = new Date();
        String nowTime = sdf.format(date);
        Date myTime = sdf.parse(nowTime);
        return myTime;
    }
    private SimpleDateFormat getSimpleDateFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat();
        // a???am/pm?????????
        sdf.applyPattern("yyyy-MM-dd");
        return sdf;
    }

    public RespDto finishTransaction(FinishTransactionDto finishTransactionDto) {
        String token = this.getToken();
        String tokenId = this.getTokenId(token);
        List<TransactionList> lists = this.userAndCommodityService.getLists();
        lists = lists.stream().filter(
                dto -> {
                    return dto.getTransactionId().equals(finishTransactionDto.getTransactionId());
                }
        ).collect(Collectors.toList());
        TransactionList transactionList = lists.get(0);
        List<BoughtCommodityInfoDto> boughtCommodityInfoDtos = JSONArray.parseArray(transactionList.getCommodities(), BoughtCommodityInfoDto.class);
        for (BoughtCommodityInfoDto boughtCommodityInfoDto : boughtCommodityInfoDtos) {
            if (boughtCommodityInfoDto.getCommodityId().equals(
                    finishTransactionDto.getCommodityId())) {
                if (
                        boughtCommodityInfoDto.getCommodityStatus().equals(TransactionCommodityMessageStatusEnum.HAD_BOUGHT.name()) ||
                                boughtCommodityInfoDto.getCommodityStatus().equals(TransactionCommodityMessageStatusEnum.USER_REJECT_CANCEL.name())
                ) {
                    UserInformation ownerInfo = this.userInformationMapper.selectByPrimaryKey(finishTransactionDto.getOwnerId());
                    ownerInfo.setUserBalance(ownerInfo.getUserBalance() +
                            boughtCommodityInfoDto.getPrice() * boughtCommodityInfoDto.getCount());
                    this.userInformationMapper.updateByPrimaryKey(ownerInfo);
                    boughtCommodityInfoDto.setCommodityStatus(
                            TransactionCommodityMessageStatusEnum.FINISHED.name());
                } else {
                    return RespDto.builder()
                            .status("500")
                            .message("???????????????")
                            .build();
                }
            }

        }
        transactionList.setCommodities(JSONArray.toJSONString(boughtCommodityInfoDtos));
        this.transactionListMapper.updateByPrimaryKey(transactionList);
        return RespDto.builder()
                .message("??????????????????")
                .status("200")
                .build();
    }

    /**
     * ??????????????????????????? +??????
     * @param alterCartDto
     * @return
     */
    public RespDto alterUserCart(AlterCartDto alterCartDto) {
        String token = this.getToken();
        String tokenId = this.getTokenId(token);
        ShoppingCart shoppingCart = this.shoppingCartMapper.selectByPrimaryKey(tokenId);
        List<CartCommoditiesDTO> cartCommoditiesDTOS = JSONArray.parseArray(shoppingCart.getCommodities(), CartCommoditiesDTO.class);
        for (CartCommoditiesDTO dto : cartCommoditiesDTOS) {
            if (alterCartDto.getCommodityId().equals(dto.getCommodityId())) {
                switch (alterCartDto.getType()){
                    case "ADD":
                        dto.setCount(dto.getCount() + 1);
                        break;
                    case "DEL":
                        if (dto.getCount() > 1){
                            dto.setCount(dto.getCount() - 1);
                        }else {
                            return RespDto.builder()
                                    .status("500")
                                    .message("????????????????????????")
                                    .build();
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + alterCartDto.getType());
                }

            }
        }
        shoppingCart.setCommodities(JSONArray.toJSONString(cartCommoditiesDTOS));
        this.shoppingCartMapper.updateByPrimaryKey(shoppingCart);
        return RespDto.builder()
                .message("????????????????????????")
                .status("200")
                .build();
    }
}
