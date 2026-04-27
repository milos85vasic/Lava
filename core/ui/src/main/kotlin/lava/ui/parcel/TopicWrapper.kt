package lava.ui.parcel

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import lava.models.topic.BaseTopic
import lava.models.topic.Topic

@Parcelize
@TypeParceler<Topic, TopicParceler>()
class TopicWrapper(val topic: Topic) : Parcelable

private object TopicParceler : Parceler<Topic> {
    override fun create(parcel: Parcel) = BaseTopic(
        id = parcel.requireString(),
        title = parcel.requireString(),
        author = parcel.read(OptionalAuthorParceler),
        category = parcel.read(CategoryParceler),
    )

    override fun Topic.write(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(title)
        parcel.write(author, OptionalAuthorParceler, flags)
        parcel.write(category, OptionalCategoryParceler, flags)
    }
}
