package tv.own.owntv.core.profile

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.categorylock.PinAttemptLimiters
import tv.own.owntv.core.categorylock.PinDoor
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.util.Pin

/**
 * A profile is **protected** when it has a profile lock or a Settings lock (or both). Its playlists
 * are protected with it: a new profile does not inherit them, and another profile cannot copy,
 * restore over, sync, edit or delete it without one of its PINs.
 */
fun isProtectedProfile(hasProfilePin: Boolean, hasSettingsPin: Boolean): Boolean = hasProfilePin || hasSettingsPin

/**
 * The playlists a new profile inherits: every one that no protected profile uses. A playlist shared by
 * a protected and an open profile stays out. Can be empty — the new profile then adds its own.
 */
fun inheritableSourceIds(allSourceIds: List<Long>, links: List<ProfileSourceCrossRef>, protectedIds: Set<Long>): List<Long> {
    val guarded = links.filter { it.profileId in protectedIds }.map { it.sourceId }.toSet()
    return allSourceIds.filter { it !in guarded }
}

/**
 * The profiles a backup, restore or local sync may include without asking: the active one (whoever is
 * in it has passed its doors), every open one, and the protected ones [unlocked] with a PIN.
 */
fun includableProfileIds(allIds: Collection<Long>, protectedIds: Set<Long>, activeId: Long, unlocked: Set<Long>): Set<Long> =
    allIds.filterTo(mutableSetOf()) { it == activeId || it !in protectedIds || it in unlocked }

/** Of the profiles a restore or sync would change ([touched]), the protected ones still to be unlocked. */
fun profilesNeedingPin(touched: Collection<Long>, protectedIds: Set<Long>, activeId: Long, unlocked: Set<Long>): List<Long> =
    touched.filter { it != activeId && it in protectedIds && it !in unlocked }.distinct()

/** Reads which profiles are protected, and checks a PIN typed for one of them. */
class ProfileProtection(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val limiters: PinAttemptLimiters,
) {
    suspend fun protectedIds(): Set<Long> = profileDao.getAllOnce().filter { isProtected(it) }.mapTo(mutableSetOf()) { it.id }

    suspend fun isProtected(profile: ProfileEntity): Boolean =
        isProtectedProfile(hasProfilePin = profile.pinHash != null, hasSettingsPin = settings.settingsLockPin(profile.id).first() != null)

    /** The playlists a profile created now would inherit. */
    suspend fun inheritableSourceIds(): List<Long> =
        inheritableSourceIds(sourceDao.allSourceIds(), sourceDao.allLinks(), protectedIds())

    /** The profiles that may go into a backup or a local sync now, given the ones [unlocked] with a PIN. */
    suspend fun includableProfileIds(unlocked: Set<Long> = emptySet()): Set<Long> =
        includableProfileIds(profileDao.getAllOnce().map { it.id }, protectedIds(), settings.activeProfileId.first(), unlocked)

    /**
     * The profiles a restore or merge must leave as they are: every protected one except the active one
     * and those [unlocked] with a PIN.
     */
    suspend fun heldBack(unlocked: Set<Long> = emptySet(), activeId: Long? = null): Set<Long> {
        val active = activeId ?: settings.activeProfileId.first()
        return protectedIds().filterTo(mutableSetOf()) { it != active && it !in unlocked }
    }

    /** The protected profiles among [touched] that must be unlocked before they are changed. */
    suspend fun needingPin(touched: Collection<Long>, unlocked: Set<Long> = emptySet()): List<ProfileEntity> {
        val ids = profilesNeedingPin(touched, protectedIds(), settings.activeProfileId.first(), unlocked)
        val byId = profileDao.getAllOnce().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * [pin] against [profile]'s profile PIN or its Settings PIN — either opens it. Wrong entries count
     * on the profile door, never on a category or the Settings one.
     */
    suspend fun verify(profile: ProfileEntity, pin: String): PinAttempt {
        val settingsPin = settings.settingsLockPin(profile.id).first()
        return limiters.of(PinDoor.PROFILE).attempt { matches(pin, profile.pinHash, settingsPin) }
    }

    /** Whole seconds before the profile door checks an entry again. */
    fun waitSeconds(): Int = limiters.of(PinDoor.PROFILE).waitSeconds()

    internal companion object {
        /** A missing PIN never matches: [Pin.verify] lets anything through when nothing is stored. */
        fun matches(pin: String, profilePin: String?, settingsPin: String?): Boolean =
            (profilePin != null && Pin.verify(pin, profilePin)) ||
                (settingsPin != null && Pin.verifyCustomize(pin, settingsPin))
    }
}
