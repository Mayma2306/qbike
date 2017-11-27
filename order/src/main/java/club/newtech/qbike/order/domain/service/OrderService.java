package club.newtech.qbike.order.domain.service;

import club.newtech.qbike.order.domain.core.entity.DriverPosition;
import club.newtech.qbike.order.domain.core.root.Order;
import club.newtech.qbike.order.domain.core.vo.CustomerVo;
import club.newtech.qbike.order.domain.core.vo.DriverVo;
import club.newtech.qbike.order.domain.core.vo.IntentionVo;
import club.newtech.qbike.order.domain.core.vo.Status;
import club.newtech.qbike.order.domain.repository.DriverPositionRepository;
import club.newtech.qbike.order.domain.repository.OrderRepository;
import club.newtech.qbike.order.infrastructure.UserRibbonHystrixApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.concurrent.DelayQueue;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderService.class);
    @Autowired
    UserRibbonHystrixApi userService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    private DelayQueue<IntentionVo> intentions = new DelayQueue<>();
    @Autowired
    private DriverPositionRepository driverPositionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SequenceFactory sequenceFactory;

    private String generateOrderId() {
        return "T" + String.format("%010d", sequenceFactory.generate("order"));
    }


    public void put(IntentionVo intentionVo) {
        LOGGER.info(String.format("put a vo %s to queue", intentionVo));
        this.intentions.put(intentionVo);
    }

    @Async
    public void handleIntention() {
        LOGGER.info("start handling intention loop");
        for (; ; ) {
            try {
                IntentionVo intentionVo = intentions.take();
                if (intentionVo != null) {
                    LOGGER.info(String.format("get a vo %s", intentionVo));
                    Circle circle = new Circle(new Point(intentionVo.getStartLong(), intentionVo.getStartLat()), //
                            new Distance(500, RedisGeoCommands.DistanceUnit.METERS));
                    GeoResults<RedisGeoCommands.GeoLocation<String>> result =
                            redisTemplate.opsForGeo().geoRadius("Drivers", circle);
                    if (result.getContent().size() == 0) {
                        LOGGER.info("{} 没找到匹配, 放入队列重新等待", intentionVo.getCustomerId());
                        IntentionVo newVo = new IntentionVo(intentionVo.getCustomerId(),
                                intentionVo.getStartLong(), intentionVo.getStartLat(),
                                intentionVo.getDestLong(), intentionVo.getDestLat(),
                                intentionVo.getMid(),
                                2000L);
                        intentions.put(newVo);
                    } else {
                        LOGGER.info("获取附近司机为{} 目前的策略为取首位",
                                result.getContent().stream()
                                        .map(x -> x.getContent().getName())
                                        .collect(Collectors.joining(",")));
                        String driverId = result.getContent().get(0).getContent().getName();
                        LOGGER.info("{} 匹配到的司机ID为 {}", intentionVo.getCustomerId(), driverId);
                        createOrder(intentionVo, driverId);
                    }
                }
            } catch (InterruptedException e) {
                LOGGER.error("Error happended", e);
            }
        }
    }

    @Transactional
    protected void createOrder(IntentionVo intention, String driverId) {
        //在调用远程user服务获取用户信息的时候，必须有熔断，否则在事务中很危险
        Order order = new Order();
        order.setOid(generateOrderId());
        CustomerVo customerVo = userService.findCustomerById(Integer.parseInt(intention.getCustomerId()));
        DriverVo driverVo = userService.findDriverById(Integer.parseInt(driverId));
        order.setCustomer(customerVo);
        order.setDriver(driverVo);
        order.setOrderStatus(Status.OPENED);
        order.setOpened(new Date());
        order.setStartLong(intention.getStartLong());
        order.setStartLat(intention.getStartLat());
        order.setDestLong(intention.getDestLong());
        order.setDestLat(intention.getDestLat());
        order.setIntentionId(intention.getMid());
        orderRepository.save(order);
    }

    @Transactional
    public void handlePosition(int driverId, double longitude, double latitude) {

        LOGGER.info("start handling position update");
        DriverVo driver = userService.findDriverById(driverId);
        if (driver != null) {
            DriverPosition dp = new DriverPosition();
            dp.setDriverVo(driver);
            dp.setCurrentLongitude(longitude);
            dp.setCurrentLatitude(latitude);
            dp.setDId(driverId);
            dp.setUpdateTime(new Date());
            driverPositionRepository.save(dp);

            redisTemplate.opsForGeo().geoAdd("Drivers", new Point(longitude, latitude), String.valueOf(driverId));
        }

    }

}