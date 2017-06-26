package org.qcri.rheem.basic.operators;

import org.apache.commons.lang3.Validate;
import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.function.PredicateDescriptor;
import org.qcri.rheem.core.optimizer.ProbabilisticDoubleInterval;
import org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimator;
import org.qcri.rheem.core.optimizer.cardinality.DefaultCardinalityEstimator;
import org.qcri.rheem.core.plan.rheemplan.UnaryToUnaryOperator;
import org.qcri.rheem.core.types.DataSetType;

import java.util.Optional;


/**
 * This operator returns the distinct elements in this dataset.
 */
public class DistinctOperator<Type> extends UnaryToUnaryOperator<Type, Type> {

    protected final PredicateDescriptor<Type> predicateDescriptor;


    /**
     * Creates a new instance.
     *
     * @param type type of the dataunit elements
     */
    public DistinctOperator(DataSetType<Type> type) {
        super(type, type, false);
        this.predicateDescriptor = null;
    }

    public DistinctOperator(DataSetType<Type> type, PredicateDescriptor<Type> predicateDescriptor) {
        super(type, type, false);
        this.predicateDescriptor = predicateDescriptor;
    }

    /**
     * Creates a new instance.
     *
     * @param typeClass type of the dataunit elements
     */
    public DistinctOperator(Class<Type> typeClass) {
        this(DataSetType.createDefault(typeClass));
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public DistinctOperator(DistinctOperator<Type> that) {
        super(that);
        this.predicateDescriptor = null;
    }

    public PredicateDescriptor<Type> getPredicateDescriptor() {
        return this.predicateDescriptor;
    }

    public String getSelectKeyString(){
        if (this.getPredicateDescriptor() != null && this.getPredicateDescriptor().getUdfSelectivity() != null){
            return this.getPredicateDescriptor().getUdfSelectivityKeyString();
        } else {
            return "";
        }
    }

    @Override
    public Optional<CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        Validate.inclusiveBetween(0, this.getNumOutputs() - 1, outputIndex);
        // TODO: Come up with a dynamic estimator.
        // Assume with a confidence of 0.7 that 70% of the data quanta are pairwise distinct.
        return Optional.of(new DefaultCardinalityEstimator(0.7d, 1, this.isSupportingBroadcastInputs(),
                inputCards -> (long) (inputCards[0] * 0.7d)));
    }
}
