package simpledb;

import java.util.*;
import java.io.*;

/**
 * Each instance of HeapPage stores data for one page of HeapFiles and
 * implements the Page interface that is used by BufferPool.
 *
 * @see HeapFile.
 *
 * @see BufferPool.
 */
public class HeapPage implements Page {

  private final HeapPageId pid;
  private final TupleDesc td;
  private final byte header[];
  private final Tuple tuples[];
  private final int numSlots;

  private TransactionId lastDirty;

  private byte[] oldData;
  private final Byte oldDataLock = new Byte((byte) 0);

  /**
   * Create a HeapPage from a set of bytes of data read from disk.
   *
   * The format of a HeapPage is a set of header bytes indicating
   * the slots of the page that are in use, some number of tuple slots.
   *
   * SlotId starts from 0.
   *
   * Specifically, the number of tuples is equal to:
   *    floor((BufferPool.getPageSize()*8) / (tuple size * 8 + 1)).
   *
   * Where tuple size is the size of tuples in this database table, which can
   * be determined via {@link Catalog#getTupleDesc}.
   *
   * The number of 8-bit header words is equal to:
   *    ceiling(no. tuple slots / 8).
   *
   * @see Database#getCatalog.
   *
   * @see Catalog#getTupleDesc.
   *
   * @see BufferPool#getPageSize.
   */
  public HeapPage(HeapPageId id, byte[] data) throws IOException {
    this.pid = id;
    this.td = Database.getCatalog().getTupleDesc(id.getTableId());
    this.numSlots = getNumTuples();

    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

    // Allocate and read the header slots of this page.
    header = new byte[getHeaderSize()];
    for (int i = 0; i < header.length; i++) {
      header[i] = dis.readByte();
    }

    tuples = new Tuple[numSlots];
    try {
      // Allocate and read the actual records of this page.
      for (int i = 0; i < tuples.length; i++) {
        tuples[i] = readNextTuple(dis, i);
      }
    } catch (NoSuchElementException e) {
      e.printStackTrace();
    }
    dis.close();

    setBeforeImage();
  }

  /** Retrieve the number of tuples on this page. */
  private int getNumTuples() {
    return (BufferPool.getPageSize() * 8) / (td.getSize() * 8 + 1);
  }

  /**
   * Computes the number of bytes in the header of a page in a HeapFile with
   * each tuple occupying tupleSize bytes.
   */
  private int getHeaderSize() {
    return (numSlots + 7) / 8;
  }

  /** Returns a view of this page before it was modified. Used by recovery. */
  public HeapPage getBeforeImage(){
    try {
      byte[] oldDataRef = null;
      synchronized (oldDataLock) {
        oldDataRef = oldData;
      }
      return new HeapPage(pid, oldDataRef);
    } catch (IOException e) {
      e.printStackTrace();
      // Should never happen, we parsed it OK before!
      System.exit(1);
    }
    return null;
  }

  public void setBeforeImage() {
    synchronized (oldDataLock) {
      oldData = getPageData().clone();
    }
  }

  /**
   * Returns the PageId associated with this page.
   */
  public HeapPageId getId() {
    return pid;
  }

  /**
   * Suck up tuples from the source file.
   */
  private Tuple readNextTuple(DataInputStream dis, int slotId)
      throws NoSuchElementException {
    // If associated bit is not set, read forward to the next tuple, and
    // return null.
    if (!isSlotUsed(slotId)) {
      for (int i = 0; i < td.getSize(); i++) {
        try {
          dis.readByte();
        } catch (IOException e) {
          throw new NoSuchElementException("error reading empty tuple");
        }
      }
      return null;
    }

    // Read fields in the tuple.
    Tuple t = new Tuple(td);
    RecordId rid = new RecordId(pid, slotId);
    t.setRecordId(rid);
    try {
      for (int j = 0; j < td.numFields(); j++) {
        Field f = td.getFieldType(j).parse(dis);
        t.setField(j, f);
      }
    } catch (java.text.ParseException e) {
      e.printStackTrace();
      throw new NoSuchElementException("parsing error!");
    }

    return t;
  }

  /**
   * Generates a byte array representing the contents of this page.
   * Used to serialize this page to disk.
   *
   * The invariant here is that it should be possible to pass the byte
   * array generated by getPageData to the HeapPage constructor and
   * have it produce an identical HeapPage object.
   *
   * @see #HeapPage.
   *
   * @return A byte array correspond to the bytes of this page.
   */
  public byte[] getPageData() {
    int len = BufferPool.getPageSize();
    ByteArrayOutputStream baos = new ByteArrayOutputStream(len);
    DataOutputStream dos = new DataOutputStream(baos);

    // Create the header of the page.
    for (int i = 0; i < header.length; i++) {
      try {
        dos.writeByte(header[i]);
      } catch (IOException e) {
        // This really shouldn't happen.
        e.printStackTrace();
      }
    }

    // Create the tuples.
    for (int i = 0; i < tuples.length; i++) {
      // Empty slot.
      if (!isSlotUsed(i)) {
        for (int j = 0; j < td.getSize(); j++) {
          try {
            dos.writeByte(0);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
        continue;
      }

      // Non-empty slot.
      for (int j = 0; j < td.numFields(); j++) {
        Field f = tuples[i].getField(j);
        try {
          f.serialize(dos);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    // Padding.
    int zerolen = BufferPool.getPageSize();
    zerolen -= (header.length + td.getSize() * tuples.length);

    byte[] zeroes = new byte[zerolen];
    try {
      dos.write(zeroes, 0, zerolen);
    } catch (IOException e) {
      e.printStackTrace();
    }

    try {
      dos.flush();
    } catch (IOException e) {
      e.printStackTrace();
    }

    return baos.toByteArray();
  }

  /**
   * Static method to generate a byte array corresponding to an empty HeapPage.
   *
   * Used to add new, empty pages to the file. Passing the results of
   * this method to the HeapPage constructor will create a HeapPage with
   * no valid tuples in it.
   *
   * @return The returned ByteArray.
   */
  public static byte[] createEmptyPageData() {
    int len = BufferPool.getPageSize();
    return new byte[len]; // all 0
  }

  /**
   * Delete the specified tuple from the page;  the tuple should be updated to
   * reflect that it is no longer stored on any page.
   *
   * @throws DbException If this tuple is not on this page, or tuple slot is
   * already empty.
   *
   * @param t The tuple to delete.
   */
  public void deleteTuple(Tuple t) throws DbException {
    RecordId rid = t.getRecordId();

    if (!rid.getPageId().equals(pid) ||
        rid.tupleno() < 0 || rid.tupleno() >= numSlots) {
      throw new DbException("tuple is not on this page");
    }
    if (!isSlotUsed(rid.tupleno())) {
      throw new DbException("tuple slot is already empty");
    }
    markSlotUsed(rid.tupleno(), false);
  }

  /**
   * Adds the specified tuple to the page;  the tuple should be updated to
   * reflect that it is now stored on this page.
   *
   * @throws DbException If the page is full (no empty slots) or TupleDesc
   * is mismatch.
   *
   * @param t The tuple to add.
   */
  public void insertTuple(Tuple t) throws DbException {
    if (!t.getTupleDesc().equals(td)) {
      throw new DbException("TupleDesc is mismatch");
    }
    for (int i = 0; i < numSlots; ++i) {
      if (!isSlotUsed(i)) {
        markSlotUsed(i, true);
        t.setRecordId(new RecordId(pid, i));
        tuples[i] = t;
        return;
      }
    }
    throw new DbException("the page is full");
  }

  /**
   * Marks this page as dirty/not dirty and record that transaction
   * that did the dirtying.
   */
  public void markDirty(boolean dirty, TransactionId tid) {
    // TODO(foreverbell): FIXME FIXME.
    if (dirty) {
      lastDirty = tid;
    } else {
      lastDirty = null;
    }
  }

  /**
   * Returns the tid of the transaction that last dirtied this page, or null
   * if the page is not dirty.
   */
  public TransactionId isDirty() {
    return lastDirty;
  }

  /**
   * Returns the number of empty slots on this page.
   */
  public int getNumEmptySlots() {
    int ret = 0;

    for (int i = 0; i < numSlots; ++i) {
      if (!isSlotUsed(i)) {
        ++ret;
      }
    }
    return ret;
  }

  /**
   * Returns true if associated slot on this page is filled.
   */
  public boolean isSlotUsed(int i) {
    return ((header[i / 8] >> (i % 8)) & 1) == 1;
  }

  /**
   * Abstraction to fill or clear a slot on this page.
   */
  private void markSlotUsed(int i, boolean value) {
    int n = i / 8, b = i % 8;

    if (value) {
      header[n] |= 1 << b;
    } else {
      header[n] &= ~(1 << b);
    }
  }

  private class TupleIterator implements Iterator<Tuple> {
    private int index;
    private final HeapPage hp;

    public TupleIterator(HeapPage hp) {
      this.hp = hp;
      this.index = -1;

      locateNext();
    }

    private void locateNext() {
      if (index >= hp.numSlots) {
        return;
      }
      index += 1;
      while (index < hp.numSlots) {
        if (hp.isSlotUsed(index)) {
          break;
        } else ++index;
      }
    }

    public boolean hasNext() {
      return index < hp.numSlots;
    }

    public Tuple next() {
      if (index >= hp.numSlots) {
        return null;
      }
      Tuple v = hp.tuples[index];
      locateNext();
      return v;
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Returns an iterator over all tuples on this page (calling remove on this
   * iterator throws an UnsupportedOperationException).
   * Note that this iterator shouldn't return tuples in empty slots!
   */
  public Iterator<Tuple> iterator() {
    return new TupleIterator(this);
  }
}
