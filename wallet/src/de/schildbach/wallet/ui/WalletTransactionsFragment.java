/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.Purpose;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.AsyncTaskLoader;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.BitmapFragment;
import de.schildbach.wallet.util.Qr;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class WalletTransactionsFragment extends FancyListFragment implements LoaderCallbacks<List<Transaction>>, OnSharedPreferenceChangeListener
{
	public enum Direction
	{
		RECEIVED, SENT
	}

	private AbstractWalletActivity activity;
	private WalletApplication application;
	private Configuration config;
	private Wallet wallet;
	private ContentResolver resolver;
	private LoaderManager loaderManager;

	private TransactionsListAdapter adapter;

	@Nullable
	private Direction direction;

	private final Handler handler = new Handler();

	private static final int ID_TRANSACTION_LOADER = 0;
	private static final String ARG_DIRECTION = "direction";

	private static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
	private static final Uri KEY_ROTATION_URI = Uri.parse("http://bitcoin.org/en/alert/2013-08-11-android");

	private static final Logger log = LoggerFactory.getLogger(WalletTransactionsFragment.class);

	private final ContentObserver addressBookObserver = new ContentObserver(handler)
	{
		@Override
		public void onChange(final boolean selfChange)
		{
			adapter.clearLabelCache();
		}
	};

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
		this.resolver = activity.getContentResolver();
		this.loaderManager = getLoaderManager();
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setRetainInstance(true);
		setHasOptionsMenu(true);

		this.direction = null;

		adapter = new TransactionsListAdapter(activity, wallet, application.maxConnectedPeers());
		setListAdapter(adapter);
	}

	@Override
	public void onResume()
	{
		super.onResume();

		resolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true, addressBookObserver);

		config.registerOnSharedPreferenceChangeListener(this);

		final Bundle args = new Bundle();
		args.putSerializable(ARG_DIRECTION, direction);
		loaderManager.initLoader(ID_TRANSACTION_LOADER, args, this);

		wallet.addEventListener(transactionChangeListener, Threading.SAME_THREAD);

		updateView();
	}

	@Override
	public void onPause()
	{
		wallet.removeEventListener(transactionChangeListener);
		transactionChangeListener.removeCallbacks();

		loaderManager.destroyLoader(ID_TRANSACTION_LOADER);

		config.unregisterOnSharedPreferenceChangeListener(this);

		resolver.unregisterContentObserver(addressBookObserver);

		super.onPause();
	}

	@Override
	public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater)
	{
		inflater.inflate(R.menu.wallet_transactions_fragment_options, menu);

		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public void onPrepareOptionsMenu(final Menu menu)
	{
		if (direction == null)
			menu.findItem(R.id.wallet_transactions_options_filter_all).setChecked(true);
		else if (direction == Direction.RECEIVED)
			menu.findItem(R.id.wallet_transactions_options_filter_received).setChecked(true);
		else if (direction == Direction.SENT)
			menu.findItem(R.id.wallet_transactions_options_filter_sent).setChecked(true);

		super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		final int itemId = item.getItemId();
		if (itemId == R.id.wallet_transactions_options_filter_all)
			direction = null;
		else if (itemId == R.id.wallet_transactions_options_filter_received)
			direction = Direction.RECEIVED;
		else if (itemId == R.id.wallet_transactions_options_filter_sent)
			direction = Direction.SENT;
		else
			return false;

		item.setChecked(true);

		final Bundle args = new Bundle();
		args.putSerializable(ARG_DIRECTION, direction);
		loaderManager.restartLoader(ID_TRANSACTION_LOADER, args, this);

		return true;
	}

	@Override
	public void onListItemClick(final ListView l, final View v, final int position, final long id)
	{
		final Transaction tx = (Transaction) adapter.getItem(position);

		if (tx == null)
			handleBackupWarningClick();
		else if (tx.getPurpose() == Purpose.KEY_ROTATION)
			handleKeyRotationClick();
		else
			handleTransactionClick(tx);
	}

	private void handleTransactionClick(final Transaction tx)
	{
		activity.startActionMode(new ActionMode.Callback()
		{
			private final boolean txSent = tx.getValue(wallet).signum() < 0;
			private final Address txAddress = txSent ? WalletUtils.getToAddressOfSent(tx, wallet) : WalletUtils
					.getWalletAddressOfReceived(tx, wallet);
			private final byte[] txSerialized = tx.unsafeBitcoinSerialize();

			private static final int SHOW_QR_THRESHOLD_BYTES = 2500;

			@Override
			public boolean onCreateActionMode(final ActionMode mode, final Menu menu)
			{
				final MenuInflater inflater = mode.getMenuInflater();
				inflater.inflate(R.menu.wallet_transactions_context, menu);

				return true;
			}

			@Override
			public boolean onPrepareActionMode(final ActionMode mode, final Menu menu)
			{
				try
				{
					final Date time = tx.getUpdateTime();
					final DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(activity);
					final DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(activity);

					mode.setTitle(time != null ? (DateUtils.isToday(time.getTime()) ? getString(R.string.time_today) : dateFormat.format(time))
							+ ", " + timeFormat.format(time) : null);

					final String label;
					if (tx.isCoinBase())
						label = getString(R.string.wallet_transactions_fragment_coinbase);
					else if (txAddress != null)
						label = AddressBookProvider.resolveLabel(activity, txAddress.toString());
					else
						label = "?";

					final String prefix = getString(txSent ? R.string.symbol_to : R.string.symbol_from) + " ";

					if (tx.getPurpose() != Purpose.KEY_ROTATION)
						mode.setSubtitle(label != null ? prefix + label : WalletUtils.formatAddress(prefix, txAddress,
								Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
					else
						mode.setSubtitle(null);

					menu.findItem(R.id.wallet_transactions_context_edit_address).setVisible(txAddress != null);
					menu.findItem(R.id.wallet_transactions_context_show_qr).setVisible(txSerialized.length < SHOW_QR_THRESHOLD_BYTES);

					return true;
				}
				catch (final ScriptException x)
				{
					return false;
				}
			}

			@Override
			public boolean onActionItemClicked(final ActionMode mode, final MenuItem item)
			{
				switch (item.getItemId())
				{
					case R.id.wallet_transactions_context_edit_address:
						handleEditAddress(tx);

						mode.finish();
						return true;

					case R.id.wallet_transactions_context_show_qr:
						handleShowQr();

						mode.finish();
						return true;

					case R.id.wallet_transactions_context_browse:
						startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.EXPLORE_BASE_URL + "tx/" + tx.getHashAsString())));

						mode.finish();
						return true;
				}
				return false;
			}

			@Override
			public void onDestroyActionMode(final ActionMode mode)
			{
			}

			private void handleEditAddress(final Transaction tx)
			{
				EditAddressBookEntryFragment.edit(getFragmentManager(), txAddress.toString());
			}

			private void handleShowQr()
			{
				final int size = getResources().getDimensionPixelSize(R.dimen.bitmap_dialog_qr_size);
				final Bitmap qrCodeBitmap = Qr.bitmap(Qr.encodeCompressBinary(txSerialized), size);
				BitmapFragment.show(getFragmentManager(), qrCodeBitmap);
			}
		});
	}

	private void handleKeyRotationClick()
	{
		startActivity(new Intent(Intent.ACTION_VIEW, KEY_ROTATION_URI));
	}

	private void handleBackupWarningClick()
	{
		((WalletActivity) activity).handleBackupWallet();
	}

	@Override
	public Loader<List<Transaction>> onCreateLoader(final int id, final Bundle args)
	{
		return new TransactionsLoader(activity, wallet, (Direction) args.getSerializable(ARG_DIRECTION));
	}

	@Override
	public void onLoadFinished(final Loader<List<Transaction>> loader, final List<Transaction> transactions)
	{
		final Direction direction = ((TransactionsLoader) loader).getDirection();

		adapter.replace(transactions);
		adapter.setShowBackupWarning(direction == null || direction == Direction.RECEIVED);

		final SpannableStringBuilder emptyText = new SpannableStringBuilder(
				getString(direction == Direction.SENT ? R.string.wallet_transactions_fragment_empty_text_sent
						: R.string.wallet_transactions_fragment_empty_text_received));
		emptyText.setSpan(new StyleSpan(Typeface.BOLD), 0, emptyText.length(), SpannableStringBuilder.SPAN_POINT_MARK);
		if (direction != Direction.SENT)
			emptyText.append("\n\n").append(getString(R.string.wallet_transactions_fragment_empty_text_howto));

		setEmptyText(emptyText);
	}

	@Override
	public void onLoaderReset(final Loader<List<Transaction>> loader)
	{
		// don't clear the adapter, because it will confuse users
	}

	private final ThrottlingWalletChangeListener transactionChangeListener = new ThrottlingWalletChangeListener(THROTTLE_MS)
	{
		@Override
		public void onThrottledWalletChanged()
		{
			adapter.notifyDataSetChanged();
		}
	};

	private static class TransactionsLoader extends AsyncTaskLoader<List<Transaction>>
	{
		private LocalBroadcastManager broadcastManager;
		private final Wallet wallet;
		@Nullable
		private final Direction direction;

		private TransactionsLoader(final Context context, final Wallet wallet, @Nullable final Direction direction)
		{
			super(context);

			this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
			this.wallet = wallet;
			this.direction = direction;
		}

		public @Nullable Direction getDirection()
		{
			return direction;
		}

		@Override
		protected void onStartLoading()
		{
			super.onStartLoading();

			wallet.addEventListener(transactionAddRemoveListener, Threading.SAME_THREAD);
			broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
			transactionAddRemoveListener.onReorganize(null); // trigger at least one reload

			safeForceLoad();
		}

		@Override
		protected void onStopLoading()
		{
			broadcastManager.unregisterReceiver(walletChangeReceiver);
			wallet.removeEventListener(transactionAddRemoveListener);
			transactionAddRemoveListener.removeCallbacks();

			super.onStopLoading();
		}

		@Override
		protected void onReset()
		{
			broadcastManager.unregisterReceiver(walletChangeReceiver);
			wallet.removeEventListener(transactionAddRemoveListener);
			transactionAddRemoveListener.removeCallbacks();

			super.onReset();
		}

		@Override
		public List<Transaction> loadInBackground()
		{
			final Set<Transaction> transactions = wallet.getTransactions(true);
			final List<Transaction> filteredTransactions = new ArrayList<Transaction>(transactions.size());

			for (final Transaction tx : transactions)
			{
				final boolean sent = tx.getValue(wallet).signum() < 0;
				final boolean isInternal = tx.getPurpose() == Purpose.KEY_ROTATION;

				if ((direction == Direction.RECEIVED && !sent && !isInternal) || direction == null
						|| (direction == Direction.SENT && sent && !isInternal))
					filteredTransactions.add(tx);
			}

			Collections.sort(filteredTransactions, TRANSACTION_COMPARATOR);

			return filteredTransactions;
		}

		private final ThrottlingWalletChangeListener transactionAddRemoveListener = new ThrottlingWalletChangeListener(THROTTLE_MS, true, true, false)
		{
			@Override
			public void onThrottledWalletChanged()
			{
				safeForceLoad();
			}
		};

		private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive(final Context context, final Intent intent)
			{
				safeForceLoad();
			}
		};

		private void safeForceLoad()
		{
			try
			{
				forceLoad();
			}
			catch (final RejectedExecutionException x)
			{
				log.info("rejected execution: " + TransactionsLoader.this.toString());
			}
		}

		private static final Comparator<Transaction> TRANSACTION_COMPARATOR = new Comparator<Transaction>()
		{
			@Override
			public int compare(final Transaction tx1, final Transaction tx2)
			{
				final boolean pending1 = tx1.getConfidence().getConfidenceType() == ConfidenceType.PENDING;
				final boolean pending2 = tx2.getConfidence().getConfidenceType() == ConfidenceType.PENDING;

				if (pending1 != pending2)
					return pending1 ? -1 : 1;

				final Date updateTime1 = tx1.getUpdateTime();
				final long time1 = updateTime1 != null ? updateTime1.getTime() : 0;
				final Date updateTime2 = tx2.getUpdateTime();
				final long time2 = updateTime2 != null ? updateTime2.getTime() : 0;

				if (time1 != time2)
					return time1 > time2 ? -1 : 1;

				return tx1.getHash().compareTo(tx2.getHash());
			}
		};
	}

	@Override
	public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key)
	{
		if (Configuration.PREFS_KEY_BTC_PRECISION.equals(key))
			updateView();
	}

	private void updateView()
	{
		adapter.setFormat(config.getFormat());
		adapter.clearLabelCache();
	}
}
