package org.qcri.rheem.basic.operators;

import org.apache.commons.lang3.Validate;
import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.function.PredicateDescriptor;
import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.optimizer.ProbabilisticDoubleInterval;
import org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimate;
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
        this.predicateDescriptor = that.getPredicateDescriptor();
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


//        // Assume with a confidence of 0.7 that 70% of the data quanta are pairwise distinct.
//        return Optional.of(new DefaultCardinalityEstimator(0.7d, 1, this.isSupportingBroadcastInputs(),
//                inputCards -> (long) (inputCards[0] * 0.7d))); // TODO JRK: Do not make baseline worse



    @Override
    public Optional<org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        Validate.inclusiveBetween(0, this.getNumOutputs() - 1, outputIndex);
        return Optional.of(new DistinctOperator.CardinalityEstimator(configuration));
    }


    private class CardinalityEstimator implements org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimator {

        /**
         * The expected selectivity to be applied in this instance.
         */
        private final ProbabilisticDoubleInterval selectivity;

        public CardinalityEstimator(Configuration configuration) {
            this.selectivity = configuration.getUdfSelectivityProvider().provideFor(DistinctOperator.this.predicateDescriptor);
        }

        @Override
        public CardinalityEstimate estimate(OptimizationContext optimizationContext, CardinalityEstimate... inputEstimates) {
            Validate.isTrue(inputEstimates.length == DistinctOperator.this.getNumInputs());
            final CardinalityEstimate inputEstimate = inputEstimates[0];

            if (this.selectivity.getCoeff() == 0) {
                return new CardinalityEstimate(
                        (long) (inputEstimate.getLowerEstimate() * this.selectivity.getLowerEstimate()),
                        (long) (inputEstimate.getUpperEstimate() * this.selectivity.getUpperEstimate()),
                        inputEstimate.getCorrectnessProbability() * this.selectivity.getCorrectnessProbability()
                );
            } else {
                return new CardinalityEstimate(
                        (long) (inputEstimate.getLowerEstimate() * this.selectivity.getCoeff() * inputEstimate.getLowerEstimate()),
                        (long) (inputEstimate.getUpperEstimate() * this.selectivity.getCoeff() * inputEstimate.getUpperEstimate()),
                        inputEstimate.getCorrectnessProbability() * this.selectivity.getCorrectnessProbability()
                );
            }
        }
    }
}
