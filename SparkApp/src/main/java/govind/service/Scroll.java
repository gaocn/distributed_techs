package govind.service;

import govind.rdd.ESPartitionInfo;
import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString(exclude = {"hits"})
public class Scroll implements Serializable {
	private String scrollId;
	private int hitsTotal;
	private Object[] hits;
}
