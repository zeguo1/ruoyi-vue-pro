package cn.iocoder.yudao.framework.common.core;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Key Value 的键值对
 *
 * @author 芋道源码
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyValue<K, V> implements Serializable {

    @Schema(description = "键；具体含义由所属接口说明")
    private K key;
    @Schema(description = "与键对应的值")
    private V value;

}
