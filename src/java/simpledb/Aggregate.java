package simpledb;

import java.util.*;

/**
 * The Aggregation operator that computes an aggregate (e.g., sum, avg, max,
 * min). Note that we only support aggregates over a single column, grouped by a
 * single column.
 */
public class Aggregate extends Operator {

  private static final long serialVersionUID = 1L;

  private DbIterator child;

  private final Aggregator aggregator;
  private DbIterator aggregatorIter;

  private final int afield;
  private final int gbfield;
  private final Aggregator.Op aop;
  private final TupleDesc td;

  /**
   * Constructor.
   * 
   * Implementation hint: depending on the type of afield, you will want to
   * construct an {@link IntAggregator} or {@link StringAggregator} to help
   * you with your implementation of readNext().
   * 
   * @param child The DbIterator that is feeding us tuples.
   *
   * @param afield The column over which we are computing an aggregate.
   *
   * @param gbfield The column over which we are grouping the result, or -1 if
   * there is no grouping.
   *
   * @param aop The aggregation operator to use.
   */
  public Aggregate(DbIterator child, int afield, int gbfield, Aggregator.Op aop) {
    TupleDesc child_td = child.getTupleDesc();
    Type afieldtype = child_td.getFieldType(afield);
    Type gbfieldtype = gbfield != -1 ? child_td.getFieldType(gbfield) : null;
    String gbfieldname = gbfield != -1 ? child_td.getFieldName(gbfield) : null;

    if (afieldtype == Type.INT_TYPE) {
      this.aggregator = new IntegerAggregator(gbfield, gbfieldtype, afield, aop);
    } else {
      this.aggregator = new StringAggregator(gbfield, gbfieldtype, afield, aop);
    }
    this.child = child;
    this.afield = afield;
    this.gbfield = gbfield;
    this.aop = aop;

    if (gbfield != -1) {
      td = new TupleDesc(new Type[] {gbfieldtype, Type.INT_TYPE},
          new String[] {gbfieldname, aop.toString() + " " + child_td.getFieldName(afield)});
    } else {
      td = new TupleDesc(new Type[] {Type.INT_TYPE},
          new String[] {aop.toString() + " " + child_td.getFieldName(afield)});
    }
  }

  /**
   * @return If this aggregate is accompanied by a groupby, return the groupby
   * field index in the <b>INPUT</b> tuples. If not, return
   * {@link simpledb.Aggregator#NO_GROUPING}.
   * */
  public int groupField() {
    return gbfield;
  }

  /**
   * @return If this aggregate is accompanied by a group by, return the name
   * of the groupby field in the <b>OUTPUT</b> tuples If not, return null.
   * */
  public String groupFieldName() {
    if (gbfield != -1) {
      return td.getFieldName(0);
    }
    return null;
  }

  /**
   * Returns the aggregate field.
   * */
  public int aggregateField() {
    return afield;
  }

  /**
   * Returns the name of the aggregate field in the <b>OUTPUT</b> tuples.
   * */
  public String aggregateFieldName() {
    return td.getFieldName(gbfield != -1 ? 1 : 0);
  }

  /**
   * Returns the aggregate operator.
   * */
  public Aggregator.Op aggregateOp() {
    return aop;
  }

  public static String nameOfAggregatorOp(Aggregator.Op aop) {
    return aop.toString();
  }

  public void open() throws NoSuchElementException, DbException,
      TransactionAbortedException {
    child.open();
    while (child.hasNext()) {
      aggregator.mergeTupleIntoGroup(child.next());
    }
    aggregatorIter = aggregator.iterator();
    aggregatorIter.open();
    super.open();
  }

  /**
   * Returns the next tuple. If there is a group by field, then the first
   * field is the field by which we are grouping, and the second field is the
   * result of computing the aggregate, If there is no group by field, then
   * the result tuple should contain one field representing the result of the
   * aggregate. Should return null if there are no more tuples.
   */
  protected Tuple fetchNext() throws TransactionAbortedException, DbException {
    if (aggregatorIter.hasNext()) {
      return aggregatorIter.next();
    }
    return null;
  }

  public void rewind() throws DbException, TransactionAbortedException {
    aggregatorIter.rewind();
  }

  /**
   * Returns the TupleDesc of this Aggregate. If there is no group by field,
   * this will have one field - the aggregate column. If there is a group by
   * field, the first field will be the group by field, and the second will be
   * the aggregate value column.
   * 
   * The name of an aggregate column should be informative. For example:
   * "aggName(aop) (child_td.getFieldName(afield))" where aop and afield are
   * given in the constructor, and child_td is the TupleDesc of the child
   * iterator.
   */
  public TupleDesc getTupleDesc() {
    return td;
  }

  public void close() {
    super.close();
    aggregatorIter.close();
    aggregatorIter = null;
    child.close();
  }

  @Override
  public DbIterator[] getChildren() {
    return new DbIterator[] { this.child };
  }

  @Override
  public void setChildren(DbIterator[] children) {
    if (this.child != children[0]) {
      this.child = children[0];
    }
  }
}
