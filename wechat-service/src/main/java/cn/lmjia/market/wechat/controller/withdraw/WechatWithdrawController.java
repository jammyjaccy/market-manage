package cn.lmjia.market.wechat.controller.withdraw;


import cn.lmjia.market.core.define.MarketNoticeType;
import cn.lmjia.market.core.define.MarketUserNoticeType;
import cn.lmjia.market.core.entity.ContactWay;
import cn.lmjia.market.core.entity.Login;
import cn.lmjia.market.core.entity.Manager;
import cn.lmjia.market.core.entity.request.PromotionRequest;
import cn.lmjia.market.core.entity.support.WithdrawStatus;
import cn.lmjia.market.core.entity.withdraw.WithdrawRequest_;
import cn.lmjia.market.core.model.ApiResult;
import cn.lmjia.market.core.repository.ContactWayRepository;
import cn.lmjia.market.core.repository.CustomerRepository;
import cn.lmjia.market.core.repository.WithdrawRequestRepository;
import cn.lmjia.market.core.service.*;
import com.huotu.verification.IllegalVerificationCodeException;
import com.huotu.verification.service.VerificationCodeService;
import me.jiangcai.lib.sys.service.SystemStringService;
import me.jiangcai.payment.exception.SystemMaintainException;
import me.jiangcai.user.notice.UserNoticeService;
import me.jiangcai.wx.model.message.SimpleTemplateMessageParameter;
import me.jiangcai.wx.model.message.TemplateMessageParameter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.persistence.criteria.Root;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Controller
public class WechatWithdrawController {

    private static final Log log = LogFactory.getLog(WechatWithdrawController.class);

    @Autowired
    private WithdrawService withdrawService;
    @Autowired
    private VerificationCodeService verificationCodeService;
    @Autowired
    private WithdrawRequestRepository withdrawRequestRepository;
    @Autowired
    private ReadService readService;
    @Autowired
    private SystemStringService systemStringService;
    @Autowired
    private LoginService loginService;
    @Autowired
    private WechatNoticeHelper wechatNoticeHelper;
    @Autowired
    private UserNoticeService userNoticeService;
    @Autowired
    private ContactWayService contactWayService;

    @GetMapping("/wechatWithdrawRecord")
    public String record() {
        return "wechat@withdrawRecord.html";
    }

    @GetMapping("/wechatWithdrawRecordData")
    @Transactional(readOnly = true)
    public String recordData(int page, @AuthenticationPrincipal Login login, Model model) {
        model.addAttribute("dataList", withdrawRequestRepository.findAll((root, query, cb)
                -> cb.and(
                cb.equal(root.get(WithdrawRequest_.whose), login)
                , root.get(WithdrawRequest_.withdrawStatus)
                        .in(WithdrawStatus.checkPending, WithdrawStatus.refuse, WithdrawStatus.success)
        ), new PageRequest(page, 5, new Sort(Sort.Direction.DESC, WithdrawRequest_.requestTime.getName()))));
        return "wechat@withdrawRecordData.html";
    }

    /**
     * @return 我要提现页面
     */
    @GetMapping("/wechatWithdraw")
    public String index(Model model) {
        double rate = withdrawService.getCostRateForNoInvoice().doubleValue();
        model.addAttribute("ratePercent"
                , NumberFormat.getPercentInstance(Locale.CHINA)
                        .format(rate));
        model.addAttribute("rate", rate);
        model.addAttribute("companyName", systemStringService.getCustomSystemString("withdraw.invoice.companyName"
                , null, true, String.class, "利每家科技有限公司"));
        model.addAttribute("companyAddress", systemStringService.getCustomSystemString("withdraw.invoice.companyAddress"
                , null, true, String.class, "杭州市滨江区滨盛路1508号海亮大厦1803室"));
        model.addAttribute("companyTelephone", systemStringService.getCustomSystemString("withdraw.invoice.companyTelephone"
                , null, true, String.class, "0570-88187913"));
        model.addAttribute("taxpayerCode", systemStringService.getCustomSystemString("withdraw.invoice.taxpayerCode"
                , null, true, String.class, "91330108MA28MBU173"));
        model.addAttribute("bankName", systemStringService.getCustomSystemString("withdraw.invoice.bankName"
                , null, true, String.class, "兴业银行杭州滨江支行"));
        model.addAttribute("bankAccount", systemStringService.getCustomSystemString("withdraw.invoice.bankAccount"
                , null, true, String.class, "356940100100162419"));
        model.addAttribute("content", systemStringService.getCustomSystemString("withdraw.invoice.content"
                , null, true, String.class, "服务费或劳务费的增值发票"));

        return "wechat@withdraw.html";
    }

    /**
     * @return 提现申请提交后，返回验证码验证页面
     */
    @PostMapping("/wechatWithdraw")
    @Transactional
    public String withdrawNew(String payee, String account
            , String bank, String mobile, BigDecimal withdraw,
                              boolean invoice, String logisticsCode, String logisticsCompany
            , @AuthenticationPrincipal Login login, Model model)
            throws SystemMaintainException, IOException {
        log.debug(login.getLoginName() + "申请提现");
        if (readService.currentBalance(login).getAmount().compareTo(withdraw) < 0) {
            return "redirect:/wechatWithdraw";
        }
        if (invoice)
            withdrawService.withdrawNew(login, payee, account, bank, mobile, withdraw, logisticsCode
                    , logisticsCompany);
        else
            withdrawService.withdrawNew(login, payee, account, bank, mobile, withdraw, null
                    , null);
        model.addAttribute("badCode", false);
        return toVerify(login, model, withdraw);
    }

    private String toVerify(Login login, Model model, BigDecimal withdraw) {
        String mobile = readService.mobileFor(login);
        String start = mobile.substring(0, 3);
        String end = mobile.substring(mobile.length() - 4, mobile.length());
        model.addAttribute("mosaicMobile", start + "****" + end);
        model.addAttribute("withdraw", withdraw);
        return "wechat@withdrawVerify.html";
    }

    @RequestMapping(method = RequestMethod.POST, value = "/misc/sendWithdrawCode")
    @ResponseBody
    public ApiResult sendWithdrawCode(@AuthenticationPrincipal Login login) throws IOException {
        verificationCodeService.sendCode(readService.mobileFor(login), withdrawService.withdrawVerificationType());
        return ApiResult.withOk();
    }

    /**
     * @return 手机验证码验证
     */
    @PostMapping("/withdrawVerify")
    public String withdrawVerify(@AuthenticationPrincipal Login login, String authCode, Model model, BigDecimal withdraw) {
        try {
            withdrawService.submitRequest(login, authCode);
            //向财务发送短信提醒
            RemindFinancial(login, withdraw);
        } catch (IllegalVerificationCodeException ex) {
            model.addAttribute("badCode", true);
            return toVerify(login, model, withdraw);
        }
        return "wechat@withdrawSuccess.html";
    }

    /**
     * 用户体现输入正确的验证码后,给财务发送短息提醒.
     *
     * @param login 提现的用户
     */
    private void RemindFinancial(Login login, BigDecimal withdraw) {
        //获取所有的管理者
        List<Manager> managerList = loginService.managers();

        //获取所有拥有财务权限的员工
        List<Manager> role_finance = managerList.stream().filter(manager ->
                manager.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_FINANCE"))
        ).collect(Collectors.toList());

        //模版信息类
        WithdrawSuccessRemindFinancial withdrawSuccessRemindFinancial = new WithdrawSuccessRemindFinancial();
        //注册模版信息
        wechatNoticeHelper.registerTemplateMessage(withdrawSuccessRemindFinancial, null);

//        List<WithdrawRequest> byWhose = withdrawRequestRepository.findByWhose();
//        WithdrawRequest withdrawRequest = byWhose.get(0);
//        withdrawRequest.getWhose();
//        List<Customer> byLogin = customerRepository.findByLogin(login);
//        Customer customer = byLogin.get(0);
        ContactWay contactWay = contactWayService.findByMobile(login.getLoginName());

        userNoticeService.sendMessage(null, loginService.toWechatUser(role_finance),
                null, withdrawSuccessRemindFinancial, contactWay.getName(), "提现金额" + withdraw);
    }

    private class WithdrawSuccessRemindFinancial implements MarketUserNoticeType {

        @Override
        public Collection<? extends TemplateMessageParameter> parameterStyles() {
            return Arrays.asList(
                    new SimpleTemplateMessageParameter("first", "客户申请佣金提现。")
                    , new SimpleTemplateMessageParameter("keyword1", "{0}")
                    , new SimpleTemplateMessageParameter("keyword2", "{1}")
                    , new SimpleTemplateMessageParameter("remark", "请尽快处理。")
            );
        }

        @Override
        public MarketNoticeType type() {
            return MarketNoticeType.WithdrawSuccessRemindFinancial;
        }

        @Override
        public String title() {
            return null;
        }

        @Override
        public boolean allowDifferentiation() {
            return true;
        }

        @Override
        public String defaultToText(Locale locale, Object[] parameters) {
            return "客户佣金提现申请";
        }

        @Override
        public String defaultToHTML(Locale locale, Object[] parameters) {
            return "客户佣金提现申请";
        }

        @Override
        public Class<?>[] expectedParameterTypes() {
            return new Class<?>[]{
                    String.class,//申请人 0
                    String.class //申请内容 1
            };
        }
    }
}
