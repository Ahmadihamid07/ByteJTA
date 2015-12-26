/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta.resource;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.log4j.Logger;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.resource.XATerminator;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.xa.TransactionXid;

public class XATerminatorImpl implements XATerminator {
	static final Logger logger = Logger.getLogger(XATerminatorImpl.class.getSimpleName());
	private TransactionContext transactionContext;
	private int transactionTimeout;
	private final Set<XAResourceArchive> resources = new TreeSet<XAResourceArchive>();

	public XATerminatorImpl(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public synchronized int prepare(Xid xid) throws XAException {
		int globalVote = XAResource.XA_RDONLY;
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive archive = itr.next();
			Xid branchXid = archive.getXid();
			int branchVote = archive.prepare(branchXid);
			if (branchVote == XAResource.XA_RDONLY) {
				archive.setReadonly(true);
				archive.setCompleted(true);
			} else {
				globalVote = XAResource.XA_OK;
			}
			logger.info(String.format("\t[%s] prepare: xares= %s, vote= %s",
					ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), archive, branchVote));
		}
		return globalVote;

	}

	public synchronized void commit(Xid xid, boolean onePhase) throws TransactionException, XAException {
		if (onePhase) {
			this.fireOnePhaseCommit(xid);
		} else {
			this.fireTwoPhaseCommit(xid);
		}
	}

	private void fireOnePhaseCommit(Xid xid) throws XAException {
		Iterator<XAResourceArchive> itr = this.resources.iterator();
		if (itr.hasNext()) {
			XAResourceArchive archive = itr.next();
			try {
				Xid branchXid = archive.getXid();
				archive.commit(branchXid, true);
				archive.setCommitted(true);
				archive.setCompleted(true);

				logger.info(String.format("\t[%s] commit: xares= %s, opc= %s",
						ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), archive, true));
			} catch (XAException xa) {
				switch (xa.errorCode) {
				case XAException.XA_RBCOMMFAIL:
				case XAException.XA_RBDEADLOCK:
				case XAException.XA_RBINTEGRITY:
				case XAException.XA_RBOTHER:
				case XAException.XA_RBPROTO:
				case XAException.XA_RBROLLBACK:
				case XAException.XA_RBTIMEOUT:
				case XAException.XA_RBTRANSIENT:
					archive.setRolledback(true);
					archive.setCompleted(true);
					throw new XAException(XAException.XA_RBROLLBACK);
				case XAException.XA_HEURCOM:
				case XAException.XA_HEURHAZ:
				case XAException.XA_HEURMIX:
				case XAException.XA_HEURRB:
				case XAException.XAER_RMERR:
					// should never happen
					logger.warn("An unexpected error occurred in one phase commit: code = " + xa.errorCode);
					break;
				case XAException.XAER_RMFAIL: {
					String txid = ByteUtils
							.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId());
					logger.warn("An error occurred in one phase commit: txid = " + txid);
					throw xa;
				}
				case XAException.XAER_NOTA:
				case XAException.XAER_INVAL:
				case XAException.XAER_PROTO: {
					String txid = ByteUtils
							.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId());
					logger.warn("An error occurred in one phase commit: txid = " + txid);
					throw xa;
				}
				}
			}
		}
	}

	private void throwCommitExceptionIfNecessary(boolean commitExists, boolean rollbackExists) throws XAException {
		if (commitExists && rollbackExists) {
			throw new XAException(XAException.XA_HEURMIX);
		} else if (rollbackExists) {
			throw new XAException(XAException.XA_HEURRB);
		}
	}

	private void fireTwoPhaseCommit(Xid xid) throws XAException {
		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean unFinishExists = false;
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive archive = itr.next();
			Xid branchXid = archive.getXid();
			try {
				if (archive.isCompleted()) {
					if (archive.isCommitted()) {
						commitExists = true;
					} else if (archive.isRolledback()) {
						rollbackExists = true;
					} else {
						// read-only, ignore.
					}
				} else {
					archive.commit(branchXid, false);
					commitExists = true;
					archive.setCommitted(true);
					archive.setCompleted(true);
					logger.info(String.format("\t[%s] commit: xares= %s, onePhaseCommit= %s",
							ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), archive, false));
				}
			} catch (XAException xaex) {
				if (commitExists) {
					// * @exception XAException An error has occurred. Possible XAExceptions
					// * are XA_HEURHAZ, XA_HEURCOM, XA_HEURRB, XA_HEURMIX, XAER_RMERR,
					// * XAER_RMFAIL, XAER_NOTA, XAER_INVAL, or XAER_PROTO.
					// * <P>If the resource manager did not commit the transaction and the
					// * parameter onePhase is set to true, the resource manager may throw
					// * one of the XA_RB* exceptions. Upon return, the resource manager has
					// * rolled back the branch's work and has released all held resources.
					switch (xaex.errorCode) {
					case XAException.XA_HEURHAZ:
						// Due to some failure, the work done on behalf of the specified
						// transaction branch may have been heuristically completed.
						commitExists = true;
						archive.setCommitted(true);
						archive.setHeuristic(true);
						archive.setCompleted(true);
						break;
					case XAException.XA_HEURMIX:
						// Due to a heuristic decision, the work done on behalf of the specified
						// transaction branch was partially committed and partially rolled back.
						commitExists = true;
						rollbackExists = true;
						archive.setCommitted(true);
						archive.setRolledback(true);
						archive.setHeuristic(true);
						archive.setCompleted(true);
						break;
					case XAException.XA_HEURCOM:
						// Due to a heuristic decision, the work done on behalf of
						// the specified transaction branch was committed.
						commitExists = true;
						archive.setCommitted(true);
						archive.setHeuristic(true);
						archive.setCompleted(true);
						break;
					case XAException.XA_HEURRB:
						// Due to a heuristic decision, the work done on behalf of
						// the specified transaction branch was rolled back.
						rollbackExists = true;
						archive.setRolledback(true);
						archive.setHeuristic(true);
						archive.setCompleted(true);
						break;
					case XAException.XAER_RMERR:
						// An error occurred in committing the work performed on behalf of the transaction
						// branch and the branch’s work has been rolled back. Note that returning this error
						// signals a catastrophic event to a transaction manager since other resource
						// managers may successfully commit their work on behalf of this branch. This error
						// should be returned only when a resource manager concludes that it can never
						// commit the branch and that it cannot hold the branch’s resources in a prepared
						// state. Otherwise, [XA_RETRY] should be returned.

						// TODO There's no XA_RETRY in jta.
						rollbackExists = true;
						archive.setRolledback(true);
						archive.setCompleted(true);
						break;
					case XAException.XAER_NOTA:
						// The specified XID is not known by the resource manager.
						if (archive.isCompleted() == false) {
							if (archive.isCommitted()) {
								commitExists = true;
							}
							if (archive.isRolledback()) {
								rollbackExists = true;
							}
						} else {
							int vote = archive.getVote();
							if (vote == XAResource.XA_RDONLY) {
								archive.setCompleted(true);
							} else if (vote == XAResource.XA_OK) {
								commitExists = true;
								archive.setCommitted(true);
								archive.setCompleted(true);
							} else {
								// should never happen
								rollbackExists = true;
								archive.setRolledback(true);
								archive.setCompleted(true);
							}
						}
						break;
					case XAException.XAER_RMFAIL:
						// An error occurred that makes the resource manager unavailable.
					case XAException.XAER_INVAL:
						// Invalid arguments were specified.
					case XAException.XAER_PROTO:
						// The routine was invoked in an improper context.
						unFinishExists = true;
						break;
					default:// XA_RB*
						rollbackExists = true;
						archive.setRolledback(true);
						archive.setCompleted(true);
						rollbackExists = true;
					}
				} else {
					try {
						this.rollback(xid);
						throw new XAException(XAException.XA_HEURRB);
					} catch (XAException xae) {
						switch (xae.errorCode) {
						case XAException.XA_HEURCOM:
							return;
						case XAException.XA_HEURMIX:
							throw xae;
						default:
							logger.warn("Unknown state in committing transaction phase.");
						}
					}
				}
			}
		}// end-for

		try {
			this.throwCommitExceptionIfNecessary(commitExists, rollbackExists);
		} catch (XAException xae) {
			if (unFinishExists) {
				throw new TransactionException(xae.errorCode);
			} else {
				throw xae;
			}
		}

	}

	public synchronized void rollback(Xid xid) throws TransactionException, XAException {
		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean unFinishExists = false;
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive archive = itr.next();
			Xid branchXid = archive.getXid();
			try {
				if (archive.isCompleted()) {
					if (archive.isCommitted()) {
						commitExists = true;
					} else if (archive.isRolledback()) {
						rollbackExists = true;
					} else {
						// read-only, ignore.
					}
				} else {
					archive.rollback(branchXid);
					rollbackExists = true;
					archive.setRolledback(true);
					archive.setCompleted(true);
					logger.info(String.format("\t[%s] rollback: xares= %s",
							ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), archive));
				}
			} catch (XAException xaex) {
				// * @exception XAException An error has occurred. Possible XAExceptions are
				// * XA_HEURHAZ, XA_HEURCOM, XA_HEURRB, XA_HEURMIX, XAER_RMERR, XAER_RMFAIL,
				// * XAER_NOTA, XAER_INVAL, or XAER_PROTO.
				// * <p>If the transaction branch is already marked rollback-only the
				// * resource manager may throw one of the XA_RB* exceptions. Upon return,
				// * the resource manager has rolled back the branch's work and has released
				// * all held resources.
				switch (xaex.errorCode) {
				case XAException.XA_HEURHAZ:
					// Due to some failure, the work done on behalf of the specified transaction branch
					// may have been heuristically completed. A resource manager may return this
					// value only if it has successfully prepared xid.
					commitExists = true;
					archive.setCommitted(true);
					archive.setHeuristic(true);
					archive.setCompleted(true);
					break;
				case XAException.XA_HEURMIX:
					// Due to a heuristic decision, the work done on behalf of the specified transaction
					// branch was partially committed and partially rolled back. A resource manager
					// may return this value only if it has successfully prepared xid.
					commitExists = true;
					rollbackExists = true;
					archive.setCommitted(true);
					archive.setRolledback(true);
					archive.setHeuristic(true);
					archive.setCompleted(true);
					break;
				case XAException.XA_HEURCOM:
					// Due to a heuristic decision, the work done on behalf of the specified transaction
					// branch was committed. A resource manager may return this value only if it has
					// successfully prepared xid.
					commitExists = true;
					archive.setCommitted(true);
					archive.setHeuristic(true);
					archive.setCompleted(true);
					break;
				case XAException.XA_HEURRB:
					// Due to a heuristic decision, the work done on behalf of the specified transaction
					// branch was rolled back. A resource manager may return this value only if it has
					// successfully prepared xid.
					rollbackExists = true;
					archive.setRolledback(true);
					archive.setHeuristic(true);
					archive.setCompleted(true);
					break;
				case XAException.XAER_PROTO:
					// The routine was invoked in an improper context.
					int archiveVote = archive.getVote();
					if (archiveVote == XAResource.XA_OK) {
						commitExists = true;// TODO
						archive.setCommitted(true);
						archive.setCompleted(true);
					} else if (archiveVote == XAResource.XA_RDONLY) {
						// ignore
					} else {
						try {
							archive.end(branchXid, XAResource.TMFAIL);
							archive.rollback(branchXid);
							rollbackExists = true;
							archive.setRolledback(true);
							archive.setCompleted(true);
						} catch (Exception ignore) {
							unFinishExists = true;// TODO
						}
					}
					break;
				case XAException.XAER_NOTA:
					// The specified XID is not known by the resource manager.
				case XAException.XAER_RMERR:
					// An error occurred in rolling back the transaction branch. The resource manager is
					// free to forget about the branch when returning this error so long as all accessing
					// threads of control have been notified of the branch’s state.
					rollbackExists = true;
					archive.setRolledback(true);
					archive.setCompleted(true);
					break;
				case XAException.XAER_RMFAIL:
					// An error occurred that makes the resource manager unavailable.
				case XAException.XAER_INVAL:
					// Invalid arguments were specified.
					unFinishExists = true;
					break;
				default:// XA_RB*
					// The resource manager has rolled back the transaction branch’s work and has
					// released all held resources. These values are typically returned when the
					// branch was already marked rollback-only.
					rollbackExists = true;
					archive.setRolledback(true);
					archive.setCompleted(true);
				}
			}
		}

		try {
			this.throwRollbackExceptionIfNecessary(commitExists, rollbackExists);
		} catch (XAException xae) {
			if (unFinishExists) {
				throw new TransactionException(xae.errorCode);
			} else {
				throw xae;
			}
		}

	}

	private void throwRollbackExceptionIfNecessary(boolean commitExists, boolean rollbackExists) throws XAException {
		if (commitExists && rollbackExists) {
			throw new XAException(XAException.XA_HEURMIX);
		} else if (commitExists) {
			throw new XAException(XAException.XA_HEURCOM);
		}
	}

	public int getTransactionTimeout() throws XAException {
		return this.transactionTimeout;
	}

	public boolean setTransactionTimeout(int seconds) throws XAException {
		this.transactionTimeout = seconds;
		return true;
	}

	public void start(Xid xid, int flags) throws XAException {
		throw new XAException(XAException.XAER_RMFAIL);
	}

	public void end(Xid xid, int flags) throws XAException {
		throw new XAException(XAException.XAER_RMFAIL);
	}

	public boolean isSameRM(XAResource xares) throws XAException {
		throw new XAException(XAException.XAER_RMFAIL);
	}

	public Xid[] recover(int flag) throws XAException {
		throw new XAException(XAException.XAER_RMFAIL);
	}

	public void forget(Xid xid) throws XAException {
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive archive = itr.next();
			if (archive.isHeuristic()) {
				try {
					Xid branchXid = archive.getXid();
					archive.forget(branchXid);
				} catch (XAException xae) {
					// Possible exception values are XAER_RMERR, XAER_RMFAIL
					// , XAER_NOTA, XAER_INVAL, or XAER_PROTO.
					switch (xae.errorCode) {
					case XAException.XAER_RMERR:
						logger.warn("Error Occurred in forget: " + xae.getMessage());
						break;
					case XAException.XAER_RMFAIL:
						logger.warn("Error Occurred in forget: " + xae.getMessage());
						break;
					case XAException.XAER_NOTA:
					case XAException.XAER_INVAL:
					case XAException.XAER_PROTO:
						break;
					default:
						logger.warn("Unknown state in forget.");
					}
				}
			}// end-if
		}// end-for
	}

	public boolean delistResource(XAResourceDescriptor descriptor, int flag) throws IllegalStateException,
			SystemException {

		XAResourceArchive archive = this.locateExisted(descriptor);
		if (archive == null) {
			throw new SystemException();
		}

		return this.delistResource(archive, flag);

	}

	private boolean delistResource(XAResourceArchive archive, int flag) throws SystemException,
			RollbackRequiredException {
		try {
			Xid branchXid = archive.getXid();
			archive.end(branchXid, flag);
			archive.setDelisted(true);
			logger.info(String.format("\t[%s] delist: xares= %s, flags= %s",
					ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), archive, flag));
		} catch (XAException xae) {
			logger.error("XATerminatorImpl.delistResource(XAResourceArchive, int)", xae);

			// Possible XAException values are XAER_RMERR, XAER_RMFAIL,
			// XAER_NOTA, XAER_INVAL, XAER_PROTO, or XA_RB*.
			switch (xae.errorCode) {
			case XAException.XAER_NOTA:
				// The specified XID is not known by the resource manager.
			case XAException.XAER_INVAL:
				// Invalid arguments were specified.
			case XAException.XAER_PROTO:
				// The routine was invoked in an improper context.
				return false;
			case XAException.XAER_RMFAIL:
				// An error occurred that makes the resource manager unavailable.
			case XAException.XAER_RMERR:
				// An error occurred in dissociating the transaction branch from the thread of control.
				SystemException sysex = new SystemException();
				sysex.initCause(xae);
				throw sysex;
			default:
				// XA_RB*
				RollbackRequiredException rrex = new RollbackRequiredException();
				rrex.initCause(xae);
				throw rrex;
			}
		} catch (RuntimeException ex) {
			SystemException sysex = new SystemException();
			sysex.initCause(ex);
			throw sysex;
		}

		return true;
	}

	public boolean enlistResource(XAResourceDescriptor descriptor) throws RollbackException, IllegalStateException,
			SystemException {

		XAResourceArchive archive = this.locateExisted(descriptor);
		int flags = XAResource.TMNOFLAGS;
		if (archive == null) {
			archive = new XAResourceArchive();
			archive.setDescriptor(descriptor);
			TransactionXid globalXid = this.transactionContext.getXid().getGlobalXid();
			archive.setXid(globalXid.createBranchXid());
		} else {
			flags = XAResource.TMJOIN;
		}

		return this.enlistResource(archive, flags);
	}

	private boolean enlistResource(XAResourceArchive archive, int flags) throws SystemException, RollbackException {
		try {
			Xid branchXid = archive.getXid();
			logger.info(String.format("\t[%s] enlist: xares= %s, flags: %s",
					ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), archive, flags));

			if (flags == XAResource.TMNOFLAGS) {
				long expired = this.transactionContext.getExpiredTime();
				long current = System.currentTimeMillis();
				long remains = expired - current;
				int timeout = (int) (remains / 1000L);
				archive.setTransactionTimeout(timeout);
				archive.start(branchXid, flags);
				this.resources.add(archive);
			} else if (flags == XAResource.TMJOIN) {
				archive.start(branchXid, flags);
				archive.setDelisted(false);
			} else if (flags == XAResource.TMRESUME) {
				archive.start(branchXid, flags);
				archive.setDelisted(false);
			} else {
				throw new SystemException();
			}
		} catch (XAException xae) {
			logger.error("XATerminatorImpl.enlistResource(XAResourceArchive, int)", xae);

			// Possible exceptions are XA_RB*, XAER_RMERR, XAER_RMFAIL,
			// XAER_DUPID, XAER_OUTSIDE, XAER_NOTA, XAER_INVAL, or XAER_PROTO.
			switch (xae.errorCode) {
			case XAException.XAER_DUPID:
				// * If neither TMJOIN nor TMRESUME is specified and the transaction
				// * specified by xid has previously been seen by the resource manager,
				// * the resource manager throws the XAException exception with XAER_DUPID error code.
				return false;
			case XAException.XAER_OUTSIDE:
				// The resource manager is doing work outside any global transaction
				// on behalf of the application.
			case XAException.XAER_NOTA:
				// Either TMRESUME or TMJOIN was set inflags, and the specified XID is not
				// known by the resource manager.
			case XAException.XAER_INVAL:
				// Invalid arguments were specified.
			case XAException.XAER_PROTO:
				// The routine was invoked in an improper context.
				return false;
			case XAException.XAER_RMFAIL:
				// An error occurred that makes the resource manager unavailable
			case XAException.XAER_RMERR:
				// An error occurred in associating the transaction branch with the thread of control
				SystemException sysex = new SystemException();
				sysex.initCause(xae);
				throw sysex;
			default:
				// XA_RB*
				throw new RollbackException();
			}
		} catch (RuntimeException ex) {
			throw new RollbackException();
		}

		return true;
	}

	private XAResourceArchive locateExisted(XAResourceDescriptor descriptor) {
		Iterator<XAResourceArchive> itr = this.resources.iterator();
		while (itr.hasNext()) {
			XAResourceArchive existed = itr.next();
			XAResourceDescriptor existedDescriptor = existed.getDescriptor();
			String identifier = descriptor.getIdentifier();
			String existedIdentifirer = existedDescriptor.getIdentifier();

			if (CommonUtils.equals(identifier, existedIdentifirer)) {
				try {
					if (existedDescriptor.isSameRM(descriptor)) {
						return existed;
					}
				} catch (XAException ex) {
					continue;
				} catch (RuntimeException ex) {
					continue;
				}
			}// end-if
		}// end-while
		return null;

	}

	public void resumeAllResource() throws RollbackException, SystemException {
		boolean rollbackRequired = false;
		boolean errorExists = false;
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive xares = itr.next();
			if (xares.isDelisted()) {
				try {
					this.enlistResource(xares, XAResource.TMRESUME);
				} catch (RollbackException rex) {
					rollbackRequired = true;
				} catch (SystemException rex) {
					errorExists = true;
				} catch (RuntimeException rex) {
					errorExists = true;
				}
			}
		}

		if (rollbackRequired) {
			throw new RollbackException();
		} else if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		}

	}

	public void suspendAllResource() throws RollbackException, SystemException {
		boolean rollbackRequired = false;
		boolean errorExists = false;
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive xares = itr.next();
			if (xares.isDelisted() == false) {
				try {
					this.delistResource(xares, XAResource.TMSUSPEND);
				} catch (RollbackRequiredException ex) {
					rollbackRequired = true;
				} catch (SystemException ex) {
					errorExists = true;
				} catch (RuntimeException ex) {
					errorExists = true;
				}
			}
		}

		if (rollbackRequired) {
			throw new RollbackException();
		} else if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		}
	}

	public void delistAllResource() throws RollbackException, SystemException {
		boolean rollbackRequired = false;
		boolean errorExists = false;
		for (Iterator<XAResourceArchive> itr = this.resources.iterator(); itr.hasNext();) {
			XAResourceArchive xares = itr.next();
			if (xares.isDelisted() == false) {
				try {
					this.delistResource(xares, XAResource.TMSUCCESS);
				} catch (RollbackRequiredException ex) {
					rollbackRequired = true;
				} catch (SystemException ex) {
					errorExists = true;
				} catch (RuntimeException ex) {
					errorExists = true;
				}
			}
		}// end-for

		if (rollbackRequired) {
			throw new RollbackException();
		} else if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		}

	}

	public Set<XAResourceArchive> getResourceArchives() {
		return this.resources;
	}

}
