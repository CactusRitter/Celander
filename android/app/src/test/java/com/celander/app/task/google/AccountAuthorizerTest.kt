package com.celander.app.task.google

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.celander.app.account.google.AccessTokens
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.model.AccountType
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountAuthorizerTest {
    private lateinit var db: CelanderDatabase
    private var tokenFor: (forceRefresh: Boolean) -> String? = { if (it) "fresh" else "stale" }
    private val requests = mutableListOf<Pair<String, Boolean>>()
    private lateinit var authorizer: AccountAuthorizer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CelanderDatabase::class.java).allowMainThreadQueries().build()
        authorizer = AccountAuthorizer(
            db.accountDao(),
            AccessTokens { account, force ->
                requests += account.identifier to force
                tokenFor(force)
            },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addGoogle(email: String) =
        db.accountDao().insertAccount(AccountEntity(type = AccountType.GOOGLE.name, identifier = email, displayName = email, createdAt = 0L))

    @Test
    fun tokenOfAnExistingAccountIsGranted() = runBlocking {
        addGoogle("a@example.com")
        assertEquals(AuthResult.Granted("stale"), authorizer.backgroundToken("a@example.com"))
    }

    @Test
    fun unknownAccountNeedsSignIn() = runBlocking {
        assertEquals(AuthResult.Failed(GoogleTasksStatus.NeedsReauth), authorizer.backgroundToken("nobody@example.com"))
        assertEquals(emptyList<Pair<String, Boolean>>(), requests)
    }

    @Test
    fun accountWithoutTokensNeedsSignIn() = runBlocking {
        addGoogle("a@example.com")
        tokenFor = { null }
        assertEquals(AuthResult.Failed(GoogleTasksStatus.NeedsReauth), authorizer.backgroundToken("a@example.com"))
    }

    @Test
    fun connectionProblemIsOfflineNotSignIn() = runBlocking {
        addGoogle("a@example.com")
        tokenFor = { throw IOException("offline") }
        assertEquals(AuthResult.Failed(GoogleTasksStatus.Offline), authorizer.backgroundToken("a@example.com"))
    }

    @Test
    fun rejectedTokenIsRefreshedNextTime() = runBlocking {
        addGoogle("a@example.com")
        authorizer.clearToken("stale")
        assertEquals(AuthResult.Granted("fresh"), authorizer.backgroundToken("a@example.com"))
        assertEquals(listOf("a@example.com" to false, "a@example.com" to true), requests)
    }
}
