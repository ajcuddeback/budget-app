const router = require('express').Router();
const { User, Bills, Income } = require('../../models');

router.get('/', (req, res) => {
    User.findAll({
        include: [
            {
                model: Bills,
                attributes: ['id', 'name', 'amount']
            },
            {
                model: Income,
                attributes: ['id', 'name', 'amount']
            }
        ]
    })
        .then(dbUserData => {
            if (!dbUserData) {
                res.status(404).json({ message: 'No user found!' });
                return;
            }

            res.json(dbUserData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

router.get('/:id', (req, res) => {
    User.findOne({
        where: {
            id: req.params.id
        },
        include: [
            {
                model: Bills,
                attributes: ['id', 'name', 'amount']
            },
            {
                model: Income,
                attributes: ['id', 'name', 'amount']
            }
        ]
    })
        .then(dbUserData => {
            if (!dbUserData) {
                res.status(404).json({ message: 'No user found!' });
                return;
            }

            res.json(dbUserData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

router.post('/', (req, res) => {
    User.create({
        username: req.body.username,
        first_name: req.body.first_name,
        last_name: req.body.last_name,
        password: req.body.password
    })
        .then(dbUserData => {
            req.session.save(() => {
                req.session.user_id = dbUserData.id;
                req.session.username = dbUserData.username;
                req.session.loggedIn = true;
                res.json(dbUserData);
            })

        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

router.post('/login', (req, res) => {
    User.findOne({
        where: {
            username: req.body.username,
        }
    })
        .then((dbUserData) => {
            if (!dbUserData) {
                res.status(400).json({ message: "No user found at this username!" });
                return;
            }

            const validatePassword = dbUserData.checkPassword(req.body.password);

            if (!validatePassword) {
                res.status(400).json({ message: "Your password is not correct!" });
                return;
            }

            req.session.save(() => {
                req.session.user_id = dbUserData.id;
                req.session.username = dbUserData.username;
                req.session.loggedIn = true;
                res.json(dbUserData);
            })
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

router.post("/logout", (req, res) => {
    if (req.session.loggedIn) {
        req.session.destroy(() => {
            res.status(204).end();
        })
    } else {
        res.status(404).end();
    }
})
module.exports = router