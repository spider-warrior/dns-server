package cn.t.server.dnsserver.protocol.handler;

import cn.t.server.dnsserver.constants.RecordClass;
import cn.t.server.dnsserver.constants.RecordType;
import cn.t.server.dnsserver.protocol.Header;
import cn.t.server.dnsserver.protocol.Record;
import cn.t.server.dnsserver.protocol.Request;
import cn.t.server.dnsserver.protocol.Response;
import cn.t.server.dnsserver.util.FlagUtil;
import cn.t.server.dnsserver.util.Ipv4DomainUtil;
import cn.t.server.dnsserver.util.MessageCodecUtil;
import cn.t.util.common.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author yj
 * @since 2020-01-01 11:37
 **/
@Slf4j
public class InternetIpV6DomainQueryHandler implements MessageHandler {

    @Override
    public boolean support(Request request) {
        //class: internet && type: A
        return request != null && RecordClass.IN == request.getClazz() && RecordType.AAAA == request.getType();
    }

    @Override
    public Object handler(Request request) throws IOException {
        String domain = request.getDomain();
        //读取配置域名
        String ip = Ipv4DomainUtil.getCustomDomainMapping(domain);
        if(!StringUtil.isEmpty(ip)) {
            log.info("===================================== domain : {} use local dns config, response ip: {} =====================================", domain, ip);
            Response response = new Response();
            response.setLabelCount(request.getLabelCount());
            response.setDomain(domain);
            response.setType(request.getType());
            response.setClazz(request.getClazz());
            List<Record> recordList = new ArrayList<>();
            response.setRecordList(recordList);
            //record
            Record record = new Record();
            record.setRecordType(RecordType.A);
            record.setRecordClass(RecordClass.IN);
            record.setTtl(20);
            record.setValue(ip);
            recordList.add(record);

            Header header = request.getHeader();
            short flag = header.getFlags();
            flag = FlagUtil.markResponse(flag);
            //如果客户端设置建议递归查询
            if(FlagUtil.isRecursionResolve(header.getFlags())) {
                flag = FlagUtil.markRecursionSupported(flag);
            }
            header.setFlags(flag);
            header.setAnswerCount((short)recordList.size());
            response.setHeader(header);
            return response;
        } else {
            log.info("domain: {} is not config in file, use local resolver", domain);
            //加载
            try {
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                log.info("domain: {} resolved by local resolver, record size: {}", domain, addresses.length);
                for(InetAddress inetAddress: addresses) {
                    if(inetAddress instanceof Inet6Address) {
                        Response response = new Response();
                        response.setLabelCount(request.getLabelCount());
                        response.setDomain(domain);
                        response.setType(request.getType());
                        response.setClazz(request.getClazz());
                        List<Record> recordList = new ArrayList<>();
                        response.setRecordList(recordList);
                        Record record = new Record();
                        record.setRecordType(RecordType.AAAA);
                        record.setRecordClass(RecordClass.IN);
                        record.setTtl(1);
                        record.setValue(inetAddress.getHostAddress());
                        recordList.add(record);
                        Header header = request.getHeader();
                        short flag = header.getFlags();
                        flag = FlagUtil.markResponse(flag);
                        //如果客户端设置建议递归查询
                        if(FlagUtil.isRecursionResolve(header.getFlags())) {
                            flag = FlagUtil.markRecursionSupported(flag);
                        }
                        header.setFlags(flag);
                        header.setAnswerCount((short)recordList.size());
                        response.setHeader(header);
                        return response;
                    }
                }
                throw new UnknownHostException("domain: " + domain);
            } catch (UnknownHostException e) {
                log.warn(String.format("本地路由解析域名失败, domain: %s, 即将使用114.114.114.114进行域名解析", domain), e);
                InetAddress dnsServerAddress = InetAddress.getByName("114.114.114.114");
                DatagramSocket internetSocket = new DatagramSocket();
                byte[] data = MessageCodecUtil.encodeRequest(request);
                DatagramPacket internetSendPacket = new DatagramPacket(data, data.length, dnsServerAddress, 53);
                internetSocket.send(internetSendPacket);
                byte[] receivedData = new byte[512];
                DatagramPacket internetReceivedPacket = new DatagramPacket(receivedData, receivedData.length);
                internetSocket.receive(internetReceivedPacket);
                return Arrays.copyOfRange(receivedData, 0, internetReceivedPacket.getLength());
            }
        }
    }

}