package org.wordpress.android.ui.posts.adapters;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.widget.ListPopupWindow;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.generated.MediaActionBuilder;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.PostModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.model.post.PostStatus;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.fluxc.store.MediaStore.MediaPayload;
import org.wordpress.android.fluxc.store.PostStore;
import org.wordpress.android.fluxc.store.UploadStore;
import org.wordpress.android.fluxc.store.UploadStore.UploadError;
import org.wordpress.android.ui.posts.PostUtils;
import org.wordpress.android.ui.posts.PostsListFragment;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.ui.reader.utils.ReaderImageScanner;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.ui.uploads.UploadService;
import org.wordpress.android.ui.uploads.UploadUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.ImageUtils;
import org.wordpress.android.util.SiteUtils;
import org.wordpress.android.widgets.PostListButton;
import org.wordpress.android.widgets.WPNetworkImageView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * Adapter for Posts/Pages list
 */
public class PostsListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final long ROW_ANIM_DURATION = 150;
    private static final int MAX_DISPLAYED_UPLOAD_PROGRESS = 90;

    private static final int VIEW_TYPE_POST_OR_PAGE = 0;
    private static final int VIEW_TYPE_ENDLIST_INDICATOR = 1;

    public interface OnPostButtonClickListener {
        void onPostButtonClicked(int buttonId, PostModel post);
    }

    public enum LoadMode {
        IF_CHANGED,
        FORCED
    }

    private OnLoadMoreListener mOnLoadMoreListener;
    private OnPostsLoadedListener mOnPostsLoadedListener;
    private OnPostSelectedListener mOnPostSelectedListener;
    private OnPostButtonClickListener mOnPostButtonClickListener;

    private final SiteModel mSite;
    private final int mPhotonWidth;
    private final int mPhotonHeight;
    private final int mEndlistIndicatorHeight;

    private final boolean mIsPage;
    private final boolean mIsStatsSupported;
    private final boolean mAlwaysShowAllButtons;

    private boolean mIsLoadingPosts;

    private final List<PostModel> mPosts = new ArrayList<>();
    private final List<PostModel> mHiddenPosts = new ArrayList<>();
    private final Map<Integer, String> mFeaturedImageUrls = new HashMap<>();

    private RecyclerView mRecyclerView;
    private final LayoutInflater mLayoutInflater;

    @Inject Dispatcher mDispatcher;
    @Inject protected PostStore mPostStore;
    @Inject protected MediaStore mMediaStore;
    @Inject protected UploadStore mUploadStore;

    public PostsListAdapter(Context context, @NonNull SiteModel site, boolean isPage) {
        ((WordPress) context.getApplicationContext()).component().inject(this);

        mIsPage = isPage;
        mLayoutInflater = LayoutInflater.from(context);

        mSite = site;
        mIsStatsSupported = SiteUtils.isAccessedViaWPComRest(site) && site.getHasCapabilityViewStats();

        int displayWidth = DisplayUtils.getDisplayPixelWidth(context);
        int contentSpacing = context.getResources().getDimensionPixelSize(R.dimen.content_margin);
        mPhotonWidth = displayWidth - (contentSpacing * 2);
        mPhotonHeight = context.getResources().getDimensionPixelSize(R.dimen.reader_featured_image_height);

        // endlist indicator height is hard-coded here so that its horz line is in the middle of the fab
        mEndlistIndicatorHeight = DisplayUtils.dpToPx(context, mIsPage ? 82 : 74);

        // on larger displays we can always show all buttons
        mAlwaysShowAllButtons = (displayWidth >= 1080);
    }

    public void setOnLoadMoreListener(OnLoadMoreListener listener) {
        mOnLoadMoreListener = listener;
    }

    public void setOnPostsLoadedListener(OnPostsLoadedListener listener) {
        mOnPostsLoadedListener = listener;
    }

    public void setOnPostSelectedListener(OnPostSelectedListener listener) {
        mOnPostSelectedListener = listener;
    }

    public void setOnPostButtonClickListener(OnPostButtonClickListener listener) {
        mOnPostButtonClickListener = listener;
    }

    private PostModel getItem(int position) {
        if (isValidPostPosition(position)) {
            return mPosts.get(position);
        }
        return null;
    }

    private boolean isValidPostPosition(int position) {
        return (position >= 0 && position < mPosts.size());
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecyclerView = recyclerView;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == mPosts.size()) {
            return VIEW_TYPE_ENDLIST_INDICATOR;
        }
        return VIEW_TYPE_POST_OR_PAGE;
    }

    @Override
    public int getItemCount() {
        if (mPosts.size() == 0) {
            return 0;
        } else {
            return mPosts.size() + 1; // +1 for the endlist indicator
        }
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ENDLIST_INDICATOR) {
            View view = mLayoutInflater.inflate(R.layout.endlist_indicator, parent, false);
            view.getLayoutParams().height = mEndlistIndicatorHeight;
            return new EndListViewHolder(view);
        } else if (mIsPage) {
            View view = mLayoutInflater.inflate(R.layout.page_item, parent, false);
            return new PageViewHolder(view);
        } else {
            View view = mLayoutInflater.inflate(R.layout.post_cardview, parent, false);
            return new PostViewHolder(view);
        }
    }

    private boolean canShowStatsForPost(PostModel post) {
        return mIsStatsSupported
                && PostStatus.fromPost(post) == PostStatus.PUBLISHED
                && !post.isLocalDraft()
                && !post.isLocallyChanged();
    }

    private boolean canPublishPost(PostModel post) {
        // TODO remove the hasMediaErrorForPost(post) check when we get a proper retry mechanism in place,
        // that retried to upload any failed media along with the post
        return post != null && !UploadService.isPostUploadingOrQueued(post) &&
                !UploadUtils.isMediaError(mUploadStore.getUploadErrorForPost(post)) &&
                (post.isLocallyChanged() || post.isLocalDraft() || PostStatus.fromPost(post) == PostStatus.DRAFT);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        // nothing to do if this is the static endlist indicator
        if (getItemViewType(position) == VIEW_TYPE_ENDLIST_INDICATOR) {
            return;
        }

        final PostModel post = mPosts.get(position);
        Context context = holder.itemView.getContext();

        if (holder instanceof PostViewHolder) {
            PostViewHolder postHolder = (PostViewHolder) holder;

            if (StringUtils.isNotEmpty(post.getTitle())) {
                // Unescape HTML
                String cleanPostTitle = StringEscapeUtils.unescapeHtml4(post.getTitle());
                postHolder.txtTitle.setText(cleanPostTitle);
            } else {
                postHolder.txtTitle.setText("(" + context.getResources().getText(R.string.untitled) + ")");
            }

            String cleanPostExcerpt = PostUtils.getPostListExcerptFromPost(post);

            if (StringUtils.isNotEmpty(cleanPostExcerpt)) {
                postHolder.txtExcerpt.setVisibility(View.VISIBLE);
                // Unescape HTML
                cleanPostExcerpt = StringEscapeUtils.unescapeHtml4(cleanPostExcerpt);
                // Collapse shortcodes: [gallery ids="1206,1205,1191"] -> [gallery]
                cleanPostExcerpt = PostUtils.collapseShortcodes(cleanPostExcerpt);
                postHolder.txtExcerpt.setText(cleanPostExcerpt);
            } else {
                postHolder.txtExcerpt.setVisibility(View.GONE);
            }

            showFeaturedImage(post.getId(), postHolder.imgFeatured);

            // local drafts say "delete" instead of "trash"
            if (post.isLocalDraft()) {
                postHolder.txtDate.setVisibility(View.GONE);
                postHolder.btnTrash.setButtonType(PostListButton.BUTTON_DELETE);
            } else {
                postHolder.txtDate.setText(PostUtils.getFormattedDate(post));
                postHolder.txtDate.setVisibility(View.VISIBLE);
                postHolder.btnTrash.setButtonType(PostListButton.BUTTON_TRASH);
            }

            if (UploadService.isPostUploading(post)) {
                postHolder.disabledOverlay.setVisibility(View.VISIBLE);
                postHolder.progressBar.setIndeterminate(true);
            } else if (!AppPrefs.isAztecEditorEnabled() && UploadService.isPostUploadingOrQueued(post)) {
                // Editing posts with uploading media is only supported in Aztec
                postHolder.disabledOverlay.setVisibility(View.VISIBLE);
            } else {
                postHolder.progressBar.setIndeterminate(false);
                postHolder.disabledOverlay.setVisibility(View.GONE);
            }

            updateStatusTextAndImage(postHolder.txtStatus, postHolder.imgStatus, post);
            updatePostUploadProgressBar(postHolder.progressBar, post);
            configurePostButtons(postHolder, post);
        } else if (holder instanceof PageViewHolder) {
            PageViewHolder pageHolder = (PageViewHolder) holder;
            if (StringUtils.isNotEmpty(post.getTitle())) {
                pageHolder.txtTitle.setText(post.getTitle());
            } else {
                pageHolder.txtTitle.setText("(" + context.getResources().getText(R.string.untitled) + ")");
            }

            String dateStr = getPageDateHeaderText(context, post);
            pageHolder.txtDate.setText(dateStr);

            updateStatusTextAndImage(pageHolder.txtStatus, pageHolder.imgStatus, post);
            updatePostUploadProgressBar(pageHolder.progressBar, post);

            // don't show date header if same as previous
            boolean showDate;
            if (position > 0) {
                String prevDateStr = getPageDateHeaderText(context, mPosts.get(position - 1));
                showDate = !prevDateStr.equals(dateStr);
            } else {
                showDate = true;
            }
            pageHolder.dateHeader.setVisibility(showDate ? View.VISIBLE : View.GONE);

            // no "..." more button when uploading
            pageHolder.btnMore.setVisibility(UploadService.isPostUploadingOrQueued(post) ? View.GONE :
                    View.VISIBLE);
            pageHolder.btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPagePopupMenu(v, post);
                }
            });

            // only show the top divider for the first item
            pageHolder.dividerTop.setVisibility(position == 0 ? View.VISIBLE : View.GONE);

            if (UploadService.isPostUploading(post)) {
                pageHolder.disabledOverlay.setVisibility(View.VISIBLE);
                pageHolder.progressBar.setIndeterminate(true);
            } else if (!AppPrefs.isAztecEditorEnabled() && UploadService.isPostUploadingOrQueued(post)) {
                // Editing posts with uploading media is only supported in Aztec
                pageHolder.disabledOverlay.setVisibility(View.VISIBLE);
            } else {
                pageHolder.disabledOverlay.setVisibility(View.GONE);
                pageHolder.progressBar.setIndeterminate(false);
            }
        }

        // load more posts when we near the end
        if (mOnLoadMoreListener != null && position >= mPosts.size() - 1
                && position >= PostsListFragment.POSTS_REQUEST_COUNT - 1) {
            mOnLoadMoreListener.onLoadMore();
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mOnPostSelectedListener != null) {
                    mOnPostSelectedListener.onPostSelected(post);
                }
            }
        });
    }

    private void showFeaturedImage(int postId, WPNetworkImageView imgFeatured) {
        String imageUrl = mFeaturedImageUrls.get(postId);
        if (imageUrl == null) {
            imgFeatured.setVisibility(View.GONE);
        } else if (imageUrl.startsWith("http")) {
            String photonUrl =  ReaderUtils.getResizedImageUrl(
                    imageUrl, mPhotonWidth, mPhotonHeight, !SiteUtils.isPhotonCapable(mSite));
            imgFeatured.setVisibility(View.VISIBLE);
            imgFeatured.setImageUrl(photonUrl, WPNetworkImageView.ImageType.PHOTO);
        } else {
            Bitmap bmp = ImageUtils.getWPImageSpanThumbnailFromFilePath(
                    imgFeatured.getContext(), imageUrl, mPhotonWidth);
            if (bmp != null) {
                imgFeatured.setImageUrl(null, WPNetworkImageView.ImageType.NONE);
                imgFeatured.setVisibility(View.VISIBLE);
                imgFeatured.setImageBitmap(bmp);
            } else {
                imgFeatured.setVisibility(View.GONE);
            }
        }
    }

    /*
     * returns the caption to show in the date header for the passed page - pages with the same
     * caption will be grouped together
     *  - if page is local draft, returns "Local draft"
     *  - if page is scheduled, returns formatted date w/o time
     *  - if created today or yesterday, returns "Today" or "Yesterday"
     *  - if created this month, returns the number of days ago
     *  - if created this year, returns the month name
     *  - if created before this year, returns the month name with year
     */
    private static String getPageDateHeaderText(Context context, PostModel page) {
        if (page.isLocalDraft()) {
            return context.getString(R.string.local_draft);
        } else if (PostStatus.fromPost(page) == PostStatus.SCHEDULED) {
            return DateUtils.formatDateTime(context, DateTimeUtils.timestampFromIso8601Millis(page.getDateCreated()),
                    DateUtils.FORMAT_ABBREV_ALL);
        } else {
            Date dtCreated = DateTimeUtils.dateUTCFromIso8601(page.getDateCreated());
            Date dtNow = DateTimeUtils.nowUTC();
            int daysBetween = DateTimeUtils.daysBetween(dtCreated, dtNow);
            if (daysBetween == 0) {
                return context.getString(R.string.today);
            } else if (daysBetween == 1) {
                return context.getString(R.string.yesterday);
            } else if (DateTimeUtils.isSameMonthAndYear(dtCreated, dtNow)) {
                return String.format(context.getString(R.string.days_ago), daysBetween);
            } else if (DateTimeUtils.isSameYear(dtCreated, dtNow)) {
                return new SimpleDateFormat("MMMM").format(dtCreated);
            } else {
                return new SimpleDateFormat("MMMM yyyy").format(dtCreated);
            }
        }
    }

    /*
     * user tapped "..." next to a page, show a popup menu of choices
     */
    private void showPagePopupMenu(View view, final PostModel page) {
        Context context = view.getContext();
        final ListPopupWindow listPopup = new ListPopupWindow(context);
        listPopup.setAnchorView(view);

        listPopup.setWidth(context.getResources().getDimensionPixelSize(R.dimen.menu_item_width));
        listPopup.setModal(true);
        listPopup.setAdapter(new PageMenuAdapter(context, page));
        listPopup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                listPopup.dismiss();
                if (mOnPostButtonClickListener != null) {
                    int buttonId = (int) id;
                    mOnPostButtonClickListener.onPostButtonClicked(buttonId, page);
                }
            }
        });
        listPopup.show();
    }

    private void updatePostUploadProgressBar(ProgressBar view, PostModel post) {
        if (!mUploadStore.isFailedPost(post) &&
                (UploadService.isPostUploadingOrQueued(post) || UploadService.hasInProgressMediaUploadsForPost(post))) {
            view.setVisibility(View.VISIBLE);
            int overallProgress = Math.round(UploadService.getMediaUploadProgressForPost(post) * 100);
            // Sometimes the progress bar can be stuck at 100% for a long time while further processing happens
            // Cap the progress bar at MAX_DISPLAYED_UPLOAD_PROGRESS (until we move past the 'uploading media' phase)
            view.setProgress(Math.min(MAX_DISPLAYED_UPLOAD_PROGRESS, overallProgress));
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private void updateStatusTextAndImage(TextView txtStatus, ImageView imgStatus, PostModel post) {
        Context context = txtStatus.getContext();

        if ((PostStatus.fromPost(post) == PostStatus.PUBLISHED) && !post.isLocalDraft() && !post.isLocallyChanged()) {
            txtStatus.setVisibility(View.GONE);
            imgStatus.setVisibility(View.GONE);
        } else {
            int statusTextResId = 0;
            int statusIconResId = 0;
            int statusColorResId = R.color.grey_darken_10;
            String errorMessage = null;

            UploadError reason = mUploadStore.getUploadErrorForPost(post);
            if (reason != null) {
                if (reason.mediaError != null) {
                    errorMessage = context.getString(post.isPage() ? R.string.error_media_recover_page
                            : R.string.error_media_recover_post);
                } else if (reason.postError != null) {
                    errorMessage = UploadUtils.getErrorMessageFromPostError(context, post, reason.postError);
                }
                statusIconResId = R.drawable.ic_notice_48dp;
                statusColorResId = R.color.alert_red;
            } else if (UploadService.isPostUploading(post)) {
                statusTextResId = R.string.post_uploading;
                statusIconResId = R.drawable.ic_gridicons_cloud_upload;
            } else if (UploadService.hasInProgressMediaUploadsForPost(post)) {
                statusTextResId = R.string.uploading_media;
                statusIconResId = R.drawable.ic_gridicons_cloud_upload;
            } else if(UploadService.isPostQueued(post) || UploadService.hasPendingMediaUploadsForPost(post)) {
                // the Post (or its related media if such a thing exist) *is strictly* queued
                statusTextResId = R.string.post_queued;
                statusIconResId = R.drawable.ic_gridicons_cloud_upload;
            } else if (post.isLocalDraft()) {
                statusTextResId = R.string.local_draft;
                statusIconResId = R.drawable.ic_gridicons_page;
                statusColorResId = R.color.alert_yellow;
            } else if (post.isLocallyChanged()) {
                statusTextResId = R.string.local_changes;
                statusIconResId = R.drawable.ic_gridicons_page;
                statusColorResId = R.color.alert_yellow;
            } else {
                switch (PostStatus.fromPost(post)) {
                    case DRAFT:
                        statusTextResId = R.string.post_status_draft;
                        statusIconResId = R.drawable.ic_gridicons_page;
                        statusColorResId = R.color.alert_yellow;
                        break;
                    case PRIVATE:
                        statusTextResId = R.string.post_status_post_private;
                        break;
                    case PENDING:
                        statusTextResId = R.string.post_status_pending_review;
                        statusIconResId = R.drawable.ic_gridicons_page;
                        statusColorResId = R.color.alert_yellow;
                        break;
                    case SCHEDULED:
                        statusTextResId = R.string.post_status_scheduled;
                        statusIconResId = R.drawable.ic_gridicons_calendar;
                        statusColorResId = R.color.blue_medium;
                        break;
                    case TRASHED:
                        statusTextResId = R.string.post_status_trashed;
                        statusIconResId = R.drawable.ic_gridicons_page;
                        statusColorResId = R.color.alert_red;
                        break;
                }
            }

            Resources resources = context.getResources();
            txtStatus.setTextColor(resources.getColor(statusColorResId));
            if (!TextUtils.isEmpty(errorMessage)) {
                txtStatus.setText(errorMessage);
            } else {
                txtStatus.setText(statusTextResId != 0 ? resources.getString(statusTextResId) : "");
            }
            txtStatus.setVisibility(View.VISIBLE);

            Drawable drawable = (statusIconResId != 0 ? resources.getDrawable(statusIconResId) : null);
            if (drawable != null) {
                drawable = DrawableCompat.wrap(drawable);
                DrawableCompat.setTint(drawable, resources.getColor(statusColorResId));
                imgStatus.setImageDrawable(drawable);
                imgStatus.setVisibility(View.VISIBLE);
            } else {
                imgStatus.setVisibility(View.GONE);
            }
        }
    }

    private void configurePostButtons(final PostViewHolder holder,
                                      final PostModel post) {
        // posts with local changes have preview rather than view button
        if (post.isLocalDraft() || post.isLocallyChanged()) {
            holder.btnView.setButtonType(PostListButton.BUTTON_PREVIEW);
        } else {
            holder.btnView.setButtonType(PostListButton.BUTTON_VIEW);
        }

        if (mUploadStore.getUploadErrorForPost(post) != null) {
            holder.btnPublish.setButtonType(PostListButton.BUTTON_RETRY);
        } else {
            if (PostStatus.fromPost(post) == PostStatus.SCHEDULED && post.isLocallyChanged()) {
                holder.btnPublish.setButtonType(PostListButton.BUTTON_SYNC);
            } else {
                holder.btnPublish.setButtonType(PostListButton.BUTTON_PUBLISH);
            }
        }

        boolean canShowStatsButton = canShowStatsForPost(post);
        boolean canShowPublishButton = canPublishPost(post);

        int numVisibleButtons = 3;
        if (canShowPublishButton) numVisibleButtons++;
        if (canShowStatsButton) numVisibleButtons++;

        // edit / view are always visible
        holder.btnEdit.setVisibility(View.VISIBLE);
        holder.btnView.setVisibility(View.VISIBLE);

        // if we have enough room to show all buttons, hide the back/more buttons and show stats/trash/publish
        if (mAlwaysShowAllButtons || numVisibleButtons <= 3) {
            holder.btnMore.setVisibility(View.GONE);
            holder.btnBack.setVisibility(View.GONE);
            holder.btnTrash.setVisibility(View.VISIBLE);
            holder.btnStats.setVisibility(canShowStatsButton ? View.VISIBLE : View.GONE);
            holder.btnPublish.setVisibility(canShowPublishButton ? View.VISIBLE : View.GONE);
            if (!mSite.getHasCapabilityPublishPosts()) {
                // Users with roles that lack permission to publish show Submit
                holder.btnPublish.setButtonType(PostListButton.BUTTON_SUBMIT);
            }
        } else {
            holder.btnMore.setVisibility(View.VISIBLE);
            holder.btnBack.setVisibility(View.GONE);
            holder.btnTrash.setVisibility(View.GONE);
            holder.btnStats.setVisibility(View.GONE);
            holder.btnPublish.setVisibility(View.GONE);
        }

        View.OnClickListener btnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // handle back/more here, pass other actions to activity/fragment
                int buttonType = ((PostListButton) view).getButtonType();
                switch (buttonType) {
                    case PostListButton.BUTTON_MORE:
                        animateButtonRows(holder, post, false);
                        break;
                    case PostListButton.BUTTON_BACK:
                        animateButtonRows(holder, post, true);
                        break;
                    default:
                        if (mOnPostButtonClickListener != null) {
                            mOnPostButtonClickListener.onPostButtonClicked(buttonType, post);
                        }
                        break;
                }
            }
        };
        holder.btnEdit.setOnClickListener(btnClickListener);
        holder.btnView.setOnClickListener(btnClickListener);
        holder.btnStats.setOnClickListener(btnClickListener);
        holder.btnTrash.setOnClickListener(btnClickListener);
        holder.btnMore.setOnClickListener(btnClickListener);
        holder.btnBack.setOnClickListener(btnClickListener);
        holder.btnPublish.setOnClickListener(btnClickListener);
    }

    /*
     * buttons may appear in two rows depending on display size and number of visible
     * buttons - these rows are toggled through the "more" and "back" buttons - this
     * routine is used to animate the new row in and the old row out
     */
    private void animateButtonRows(final PostViewHolder holder,
                                   final PostModel post,
                                   final boolean showRow1) {
        // first animate out the button row, then show/hide the appropriate buttons,
        // then animate the row layout back in
        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0f);
        ObjectAnimator animOut = ObjectAnimator.ofPropertyValuesHolder(holder.layoutButtons, scaleX, scaleY);
        animOut.setDuration(ROW_ANIM_DURATION);
        animOut.setInterpolator(new AccelerateInterpolator());

        animOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {

                // row 1
                holder.btnEdit.setVisibility(showRow1 ? View.VISIBLE : View.GONE);
                holder.btnView.setVisibility(showRow1 ? View.VISIBLE : View.GONE);
                holder.btnMore.setVisibility(showRow1 ? View.VISIBLE : View.GONE);
                // row 2
                holder.btnStats.setVisibility(!showRow1 && canShowStatsForPost(post) ? View.VISIBLE : View.GONE);
                holder.btnPublish.setVisibility(!showRow1 && canPublishPost(post) ? View.VISIBLE : View.GONE);
                holder.btnTrash.setVisibility(!showRow1 ? View.VISIBLE : View.GONE);
                holder.btnBack.setVisibility(!showRow1 ? View.VISIBLE : View.GONE);

                PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1f);
                PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1f);
                ObjectAnimator animIn = ObjectAnimator.ofPropertyValuesHolder(holder.layoutButtons, scaleX, scaleY);
                animIn.setDuration(ROW_ANIM_DURATION);
                animIn.setInterpolator(new DecelerateInterpolator());
                animIn.start();
            }
        });

        animOut.start();
    }

    public int getPositionForPost(PostModel post) {
        return PostUtils.indexOfPostInList(post, mPosts);
    }

    public void loadPosts(LoadMode mode) {
        if (mIsLoadingPosts) {
            AppLog.d(AppLog.T.POSTS, "post adapter > already loading posts");
        } else {
            new LoadPostsTask(mode).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    public void updateProgressForPost(@NonNull PostModel post) {
        if (mRecyclerView != null) {
            int position = getPositionForPost(post);
            if (position > -1) {
                RecyclerView.ViewHolder viewHolder = mRecyclerView.findViewHolderForAdapterPosition(position);
                if (viewHolder instanceof PostViewHolder) {
                    updatePostUploadProgressBar(((PostViewHolder) viewHolder).progressBar, post);
                } else if (viewHolder instanceof PageViewHolder) {
                    updatePostUploadProgressBar(((PageViewHolder) viewHolder).progressBar, post);
                }
            }
        }
    }

    /*
     * hides the post - used when the post is trashed by the user but the network request
     * to delete the post hasn't completed yet
     */
    public void hidePost(PostModel post) {
        mHiddenPosts.add(post);

        int position = getPositionForPost(post);
        if (position > -1) {
            mPosts.remove(position);
            if (mPosts.size() > 0) {
                notifyItemRemoved(position);

                //when page is removed update the next one in case we need to show a header
                if (mIsPage) {
                    notifyItemChanged(position);
                }
            } else {
                // we must call notifyDataSetChanged when the only post has been deleted - if we
                // call notifyItemRemoved the recycler will throw an IndexOutOfBoundsException
                // because removing the last post also removes the end list indicator
                notifyDataSetChanged();
            }
        }
    }

    public void unhidePost(PostModel post) {
        if (mHiddenPosts.remove(post)) {
            loadPosts(LoadMode.IF_CHANGED);
        }
    }

    public interface OnLoadMoreListener {
        void onLoadMore();
    }

    public interface OnPostSelectedListener {
        void onPostSelected(PostModel post);
    }

    public interface OnPostsLoadedListener {
        void onPostsLoaded(int postCount);
    }

    private class PostViewHolder extends RecyclerView.ViewHolder {
        private final TextView txtTitle;
        private final TextView txtExcerpt;
        private final TextView txtDate;
        private final TextView txtStatus;
        private final ImageView imgStatus;

        private final PostListButton btnEdit;
        private final PostListButton btnView;
        private final PostListButton btnPublish;
        private final PostListButton btnMore;

        private final PostListButton btnStats;
        private final PostListButton btnTrash;
        private final PostListButton btnBack;

        private final WPNetworkImageView imgFeatured;
        private final ViewGroup layoutButtons;

        private final View disabledOverlay;

        private final ProgressBar progressBar;

        PostViewHolder(View view) {
            super(view);

            txtTitle = (TextView) view.findViewById(R.id.text_title);
            txtExcerpt = (TextView) view.findViewById(R.id.text_excerpt);
            txtDate = (TextView) view.findViewById(R.id.text_date);
            txtStatus = (TextView) view.findViewById(R.id.text_status);
            imgStatus = (ImageView) view.findViewById(R.id.image_status);

            btnEdit = (PostListButton) view.findViewById(R.id.btn_edit);
            btnView = (PostListButton) view.findViewById(R.id.btn_view);
            btnPublish = (PostListButton) view.findViewById(R.id.btn_publish);
            btnMore = (PostListButton) view.findViewById(R.id.btn_more);

            btnStats = (PostListButton) view.findViewById(R.id.btn_stats);
            btnTrash = (PostListButton) view.findViewById(R.id.btn_trash);
            btnBack = (PostListButton) view.findViewById(R.id.btn_back);

            imgFeatured = (WPNetworkImageView) view.findViewById(R.id.image_featured);
            layoutButtons = (ViewGroup) view.findViewById(R.id.layout_buttons);

            disabledOverlay = view.findViewById(R.id.disabled_overlay);

            progressBar = (ProgressBar) view.findViewById(R.id.post_upload_progress);
        }
    }

    private class PageViewHolder extends RecyclerView.ViewHolder {
        private final TextView txtTitle;
        private final TextView txtDate;
        private final TextView txtStatus;
        private final ImageView imgStatus;
        private final ViewGroup dateHeader;
        private final View btnMore;
        private final View dividerTop;
        private final View disabledOverlay;
        private final ProgressBar progressBar;

        PageViewHolder(View view) {
            super(view);
            txtTitle = (TextView) view.findViewById(R.id.text_title);
            txtStatus = (TextView) view.findViewById(R.id.text_status);
            imgStatus = (ImageView) view.findViewById(R.id.image_status);
            btnMore = view.findViewById(R.id.btn_more);
            dividerTop = view.findViewById(R.id.divider_top);
            dateHeader = (ViewGroup) view.findViewById(R.id.header_date);
            txtDate = (TextView) dateHeader.findViewById(R.id.text_date);
            disabledOverlay = view.findViewById(R.id.disabled_overlay);
            progressBar = (ProgressBar) view.findViewById(R.id.post_upload_progress);
        }
    }

    private class EndListViewHolder extends RecyclerView.ViewHolder {
        EndListViewHolder(View view) {
            super(view);
        }
    }

    /*
     * called after the media (featured image) for a post has been downloaded - locate the post
     * and set its featured image url to the passed url
     */
    public void mediaChanged(MediaModel mediaModel) {
        // Multiple posts could have the same featured image
        List<Integer> indexList = PostUtils.indexesOfFeaturedMediaIdInList(mediaModel.getMediaId(), mPosts);
        for (int position : indexList) {
            PostModel post = getItem(position);
            if (post != null) {
                String imageUrl = mediaModel.getUrl();
                if (imageUrl != null) {
                    mFeaturedImageUrls.put(post.getId(), imageUrl);
                } else {
                    mFeaturedImageUrls.remove(post.getId());
                }
                notifyItemChanged(position);
            }
        }
    }

    private class LoadPostsTask extends AsyncTask<Void, Void, Boolean> {
        private List<PostModel> tmpPosts;
        private final ArrayList<Long> mediaIdsToUpdate = new ArrayList<>();
        private final LoadMode mLoadMode;

        LoadPostsTask(LoadMode loadMode) {
            mLoadMode = loadMode;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mIsLoadingPosts = true;
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            mIsLoadingPosts = false;
        }

        @Override
        protected Boolean doInBackground(Void... nada) {
            if (mIsPage) {
                tmpPosts = mPostStore.getPagesForSite(mSite);
            } else {
                tmpPosts = mPostStore.getPostsForSite(mSite);
            }

            // Make sure we don't return any hidden posts
            for (PostModel hiddenPost : mHiddenPosts) {
                tmpPosts.remove(hiddenPost);
            }

            // Go no further if existing post list is the same
            if (mLoadMode == LoadMode.IF_CHANGED && PostUtils.postListsAreEqual(mPosts, tmpPosts)) {
                // Always update the list if there are uploading posts
                boolean postsAreUploading = false;
                for (PostModel post : tmpPosts) {
                    if (UploadService.isPostUploadingOrQueued(post)) {
                        postsAreUploading = true;
                        break;
                    }
                }

                if (!postsAreUploading) {
                    return false;
                }
            }

            // Generate the featured image url for each post
            mFeaturedImageUrls.clear();
            boolean isPrivate = !SiteUtils.isPhotonCapable(mSite);
            for (PostModel post : tmpPosts) {
                String imageUrl = null;
                if (post.getFeaturedImageId() != 0) {
                    MediaModel media = mMediaStore.getSiteMediaWithId(mSite, post.getFeaturedImageId());
                    if (media != null) {
                        imageUrl = media.getUrl();
                    } else {
                        // If the media isn't found it means the featured image info hasn't been added to
                        // the local media library yet, so add to the list of media IDs to request info for
                        mediaIdsToUpdate.add(post.getFeaturedImageId());
                    }
                } else {
                    imageUrl = new ReaderImageScanner(post.getContent(), isPrivate).getLargestImage();
                }
                if (!TextUtils.isEmpty(imageUrl)) {
                    mFeaturedImageUrls.put(post.getId(), imageUrl);
                }
            }

            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                mPosts.clear();
                mPosts.addAll(tmpPosts);
                notifyDataSetChanged();

                if (mediaIdsToUpdate.size() > 0) {
                    for (Long mediaId : mediaIdsToUpdate) {
                        MediaModel mediaToDownload = new MediaModel();
                        mediaToDownload.setMediaId(mediaId);
                        mediaToDownload.setLocalSiteId(mSite.getId());
                        MediaPayload payload = new MediaPayload(mSite, mediaToDownload);
                        mDispatcher.dispatch(MediaActionBuilder.newFetchMediaAction(payload));
                    }
                }
            }

            mIsLoadingPosts = false;

            if (mOnPostsLoadedListener != null) {
                mOnPostsLoadedListener.onPostsLoaded(mPosts.size());
            }
        }
    }
}
