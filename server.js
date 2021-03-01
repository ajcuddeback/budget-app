const express = require('express');
const app = express();
const routes = require('./routes')
const sequelize = require('./config/connection');

const session = require('express-session');

const SequelizeStore = require('connect-session-sequelize')(session.Store);

const sess = {
    secret: process.env.SECRET,
    cookie: { httpOnly: false },
    resave: false,
    saveUninitialized: false,
    store: new SequelizeStore({
        db: sequelize,
    }),
};

app.use(session(sess));


const PORT = process.env.PORT || 3000;

app.use(express.static("public"));

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use(routes)

sequelize.sync({ force: false }).then(() => {
    app.listen(PORT, () => console.log(`App is listening on port ${PORT}`));
});